// Pipeline to deploy dependency infrastructure for app remedeation
// Roy Lofthouse (rlofthouse@dxc.com) 2022

def pstages = [:]
def files

def run_playbook(playbook, target, conf_name, extra_vars) {
    println "Running ${playbook} against ${target}"
    ansiblePlaybook(
        colorized: true,
        disableHostKeyChecking: true,
        playbook: playbook,
        //credentialsId: env.ssh_user,
        extras: '-i ' + target + ', -e target="' + target + '" --vault-password-file=' + ${env.pwfloc} + ' -u ' + env.default_ssh_user + ' -e target_app="' + env.application_set + '" -e target_env="' + env.environment + '" -e target_config="' + conf_name + '" ' + extra_vars,
    )
    
}


pipeline {
    agent {
        node {
            label env.environment
        }
    }
        
    options {
        ansiColor('css')
        parallelsAlwaysFailFast()
    }

    stages {

        stage('Update Master') {
            agent { label 'master' }
            steps {
                buildName "Checkout SCM"
                buildDescription "Update Config Only"
                withCredentials([string(credentialsId: 'GitHub_Token', variable: 'github_token')]) {
                    checkout changelog: false,  poll: false, scm: [$class: 'GitSCM', branches: [[name: '*/master']], extensions:  [[$class: 'CloneOption', noTags: true, reference: '', shallow: true], [$class: 'CleanCheckout', deleteUntrackedNestedRepositories: true]], userRemoteConfigs: [[url: 'https://' + env.github_token + '@' + git_url]]]
                }
            }
        }

        stage('Update Agent') {
            when { expression { env.Application_Set != 'Update Application Set' } }
            agent { label env.environment }
            steps {
                buildName "App Set: ${env.application_set}"
                buildDescription "Deployed to: ${env.environment}"
                withCredentials([string(credentialsId: 'GitHub_Token', variable: 'github_token')]) {
                    checkout changelog: false,  poll: false, scm: [$class: 'GitSCM', branches: [[name: '*/master']], extensions:  [[$class: 'CloneOption', noTags: true, reference: '', shallow: true], [$class: 'CleanCheckout', deleteUntrackedNestedRepositories: true]], userRemoteConfigs: [[url: 'https://' + env.github_token + '@' + git_url]]]
                }
            }
        }

        stage('Enumerate Configs') {
            when { expression { env.Application_Set != 'Update Application Set' } }
            agent { label env.environment }
            steps {
                script {
                    files = env.Configs.split(',')
                }
            }    
        }

        stage('Credential Update') {
            when { expression { env.Application_Set != 'Update Application Set' } }
            agent { label env.environment }
            steps {
                script {
                    files.each { file ->
                        pstages["${file}"] = {
                            stage("${file}") {
                                //withCredentials([usernamePassword(credentialsId: 'Default_SSH', passwordVariable: 'default_ssh_pass', usernameVariable: 'default_ssh_user'), sshUserPrivateKey(credentialsId: 'SSH_Key', keyFileVariable: 'ssh_key', usernameVariable: 'ssh_user')]) {
                                withCredentials([sshUserPrivateKey(credentialsId: 'SSH_Key', keyFileVariable: 'ssh_key', usernameVariable: 'ssh_user')]) {
                                    config = readYaml file: 'configs/' + env.environment + '/appsets/' + env.application_set + '/' + file + '.cfg'
                                    //config = readYaml file: '/data1/jenkins/test.cfg'
                                    default_ssh_password = sh (
                                        script: 'set +x && cat /data1/jenkins/passwords.csv |grep ' + config.ip4_address + '|cut -f3-  -d ,',
                                        returnStdout: true
                                    ).trim()
                                    try {
                                        run_playbook('playbooks/create_credentials.yml', config.ip4_address, file + '.cfg', ' -e ansible_ssh_pass="' + default_ssh_password +'" -e ssh_key=' + ssh_key )
                                   } catch (Exception e) {
                                        error "Failed to update SSH key!"
                                    }
                                }
                            }
                        }
                    }
                    parallel pstages
                }
            }    
        }

        stage('VM Validation') {
            when { expression { env.Application_Set != 'Update Application Set' } }
            agent { label env.environment }
            steps {
                script {
                    files.each { file ->
                        pstages["${file}"] = {
                            stage("${file}") {
                                try {
                                    config = readYaml file: 'configs/' + env.environment + '/appsets/' + env.application_set + '/' + file + '.cfg'
                                    //config = readYaml file: '/data1/jenkins/test.cfg'
                                    run_playbook('playbooks/vm_validation.yml', config.ip4_address, file + '.cfg', '')
                                } catch (errors) {
                                    error "Failed to validate VM!"
                                }
                            }
                        }
                    }
                    parallel pstages
                }
            }    
        }

        stage('Pre Validation') {
            when { expression { env.Application_Set != 'Update Application Set' } }
            agent { label env.environment }
            steps {
                script {
                    files.each { file ->
                        pstages["${file}"] = {
                            stage("${file}") {
                                try {
                                    config = readYaml file: 'configs/' + env.environment + '/appsets/' + env.application_set + '/' + file + '.cfg'
                                    run_playbook('playbooks/validation.yml', config.ip4_address, file + '.cfg', '')
                                } catch (errors) {
                                    error "Failed to run pre-validation!"
                                }
                            }
                        }
                    }
                    parallel pstages
                }
            }    
        }

        stage('Configure Packages') {
            when { expression { env.Application_Set != 'Update Application Set' } }
            agent { label env.environment }
            steps {
                script {
                    files.each { file ->
                        pstages["${file}"] = {
                            stage("${file}") {
                                withCredentials([string(credentialsId: 'Artifactory_API_key', variable: 'artifactory_api_key')]) {
                                    try {
                                        config = readYaml file: 'configs/' + env.environment + '/appsets/' + env.application_set + '/' + file + '.cfg'
                                        run_playbook('playbooks/install_packages.yml', config.ip4_address, file + '.cfg', ' -e "type=post" -e "artifactory_api_key="' + env.artifactory_api_key + '" -e "artifactory_url="' + env.artifactory_url + '"')
                                    } catch (errors) {
                                      error "Failed to install all packages!"
                                    }
                                }
                            }
                        }
                    }
                    parallel pstages
                }
            }
        }       

        stage('Post Validation') {
            when { expression { env.Application_Set != 'Update Application Set' } }
            agent { label env.environment }
            steps {
                script {
                    files.each { file ->
                        pstages["${file}"] = {
                            stage("${file}") {
                                try {
                                    config = readYaml file: 'configs/' + env.environment + '/appsets/' + env.application_set + '/' + file + '.cfg'
                                    run_playbook('playbooks/validation.yml', config.ip4_address, file + '.cfg', ' -e "type=post"')
                                } catch (errors) {
                                    error "Pipeline Failed!"
                                }
                            }
                        }
                    }
                    parallel pstages
                }
            }    
        }
    }
}