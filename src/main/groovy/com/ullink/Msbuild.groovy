package com.ullink
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.internal.ConventionTask
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem

class Msbuild extends ConventionTask {

    String version
    String msbuildDir
    def solutionFile
    def projectFile
    String loggerAssembly
    Boolean optimize
    Boolean debugSymbols
    String debugType
    String platform
    def destinationDir
    def intermediateDir
    Boolean generateDoc
    String projectName
    String configuration
    List<String> defineConstants
    List<String> targets
    String verbosity
    Map<String, Object> parameters = [:]
    Map<String, ProjectFileParser> allProjects = [:]
    String executable
    ProjectFileParser projectParsed
    IExecutableResolver resolver
    Boolean parseProject = true

    Msbuild() {
        description = 'Executes MSBuild on the specified project/solution'
        resolver =
                OperatingSystem.current().windows ? new MsbuildResolver() : new XbuildResolver()

        conventionMapping.map "solutionFile", {
            project.file(project.name + ".sln").exists() ? project.name + ".sln" : null
        }
        conventionMapping.map "projectFile", {
            project.file(project.name + ".csproj").exists() ? project.name + ".csproj" : null
        }
        conventionMapping.map "projectName", { project.name }
    }

    boolean isSolutionBuild() {
        projectFile == null && getSolutionFile() != null
    }

    boolean isProjectBuild() {
        solutionFile == null && getProjectFile() != null
    }

    Map<String, ProjectFileParser> getProjects() {
        resolveProject()
        allProjects
    }

    ProjectFileParser getMainProject() {
        if (resolveProject()) {
            projectParsed
        }
    }

    def parseProjectFile(def file) {
        logger.info "Parsing file $file ..."
        if (!new File(file.toString()).exists()) {
            throw new GradleException("Project/Solution file $file does not exist")
        }

        File tmp = File.createTempFile('ProjectFileParser', '.exe', temporaryDir)
        try {
            def src = getClass().getResourceAsStream('/META-INF/bin/ProjectFileParser.exe')
            tmp.newOutputStream().leftShift(src).close();
            def builder = resolver.executeDotNet(tmp)
            builder.command().add(file.toString())
            def proc = builder.start()
            def stderrBuffer = new StringBuffer()
            proc.consumeProcessErrorStream(stderrBuffer)
            try {
                proc.out.leftShift(JsonOutput.toJson(getInitProperties())).close()
                return new JsonSlurper().parseText(new FilterJson(proc.in).toString())
            }
            finally {
                if (proc.waitFor() != 0) {
                    stderrBuffer.eachLine { line ->
                        logger.error line
                    }
                    throw new GradleException('Project file parsing failed')
                }
            }
        } finally {
            tmp.delete()
        }
    }

    boolean resolveProject() {
        if (projectParsed == null && parseProject) {
            if (isSolutionBuild()) {
                def result = parseProjectFile(getSolutionFile())
                allProjects = result.collectEntries { [it.key, new ProjectFileParser(msbuild: this, eval: it.value)] }
                def projectName = getProjectName()
                if (projectName == null || projectName.isEmpty()) {
                    parseProject = false
                } else {
                    projectParsed = allProjects[projectName]
                    if (projectParsed == null) {
                        parseProject = false
                        logger.warn "Project ${projectName} not found in solution"
                    }
                }
            } else if (isProjectBuild()) {
                projectParsed = new ProjectFileParser(msbuild: this, eval: parseProjectFile(getProjectFile()))
                allProjects[projectParsed.projectName] = projectParsed
            }
        }
        projectParsed != null
    }

    void setTarget(String s) {
        targets = [s]
    }

    @TaskAction
    def build() {
        project.exec {
            commandLine = getCommandLineArgs()
        }
    }

    def getCommandLineArgs() {
        resolver.setupExecutable(this)

        if (msbuildDir == null) {
            throw new GradleException("$executable not found")
        }
        def commandLineArgs = resolver.executeDotNet(new File(msbuildDir, executable)).command()

        commandLineArgs += '/nologo'

        if (isSolutionBuild()) {
            commandLineArgs += project.file(getSolutionFile())
        } else if (isProjectBuild()) {
            commandLineArgs += project.file(getProjectFile())
        }

        if (loggerAssembly) {
            commandLineArgs += '/l:' + loggerAssembly
        }
        if (targets && !targets.isEmpty()) {
            commandLineArgs += '/t:' + targets.join(';')
        }

        String verb = getMSVerbosity(verbosity)
        if (verb) {
            commandLineArgs += '/v:' + verb
        }

        def cmdParameters = getInitProperties()

        cmdParameters.each {
            if (it.value) {
                commandLineArgs += '/p:' + it.key + '=' + it.value
            }
        }

        def extMap = getExtensions()?.getExtraProperties()?.getProperties()
        if (extMap != null) {
            commandLineArgs += extMap.collect { k, v ->
                v ? "/$k:$v" : "/$k"
            }
        }

        commandLineArgs
    }

    String getMSVerbosity(String verbosity) {
        if (verbosity) return verbosity
        if (logger.debugEnabled) return 'detailed'
        if (logger.infoEnabled) return 'normal'
        return 'minimal' // 'quiet'
    }

    Map getInitProperties() {
        def cmdParameters = new HashMap<String, Object>()
        if (parameters != null) {
            cmdParameters.putAll(parameters)
        }
        cmdParameters.Project = getProjectName()
        cmdParameters.GenerateDocumentation = generateDoc
        cmdParameters.DebugType = debugType
        cmdParameters.Optimize = optimize
        cmdParameters.DebugSymbols = debugSymbols
        cmdParameters.OutputPath = destinationDir == null ? null : project.file(destinationDir)
        cmdParameters.IntermediateOutputPath = intermediateDir == null ? null : project.file(intermediateDir)
        cmdParameters.Configuration = configuration
        cmdParameters.Platform = platform
        if (defineConstants != null && !defineConstants.isEmpty()) {
            cmdParameters.DefineConstants = defineConstants.join(';')
        }
        def iter = cmdParameters.iterator()
        while (iter.hasNext()) {
            Map.Entry<String, Object> entry = iter.next()
            if (entry.value == null) {
                iter.remove()
            } else if (entry.value instanceof File) {
                entry.value = entry.value.path
            } else if (!entry.value instanceof String) {
                entry.value = entry.value.toString()
            }
        }
        ['OutDir', 'OutputPath', 'BaseIntermediateOutputPath', 'IntermediateOutputPath', 'PublishDir'].each {
            if (cmdParameters[it] && !cmdParameters[it].endsWith('\\')) {
                cmdParameters[it] += '\\'
            }
        }
        return cmdParameters
    }
}
