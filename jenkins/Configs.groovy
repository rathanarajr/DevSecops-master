import jenkins.model.Jenkins
import groovy.io.FileType

def jenkins_instance  = jenkins.model.Jenkins.instance
def work_dir = "$jenkins_instance.root/workspace/deploy-deps/configs/$Environment/appsets/$Application_Set"
def currentDir = new File(work_dir)
def files = []

currentDir.eachFile(FileType.FILES) {
    files << it.name[0..<it.name.lastIndexOf('.')] + ':selected'
}

files.sort()

return files
