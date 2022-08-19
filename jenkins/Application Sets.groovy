import jenkins.model.Jenkins
import groovy.io.FileType

def jenkins_instance  = jenkins.model.Jenkins.instance
def work_dir = "$jenkins_instance.root/workspace/deploy-deps/configs/$Environment/appsets/./"
def currentDir = new File(work_dir)
def env_dirs = []

currentDir.eachFile FileType.DIRECTORIES, {
    env_dirs << it.name
}

env_dirs.sort()
env_dirs.add('Update Application Set')

return env_dirs
