def call (Map configMap){
    pipeline {
        agent any
        environment {
            project = configMap.get("project")
            component = configMap.get("component")
        }
        stages {
            stage('Build') {
                steps {
                    script{
                        sh """
                            echo 'Building..'
                            printenv
                        """
                    }
                    
                }
            }
            stage('Test') {
                steps {
                    echo 'Testing..'
                }
            }
            stage('Deploy') {
                steps {
                    echo 'Deploying....'
                }
            }
        }
    }
}