def call (Map configMap){
    pipeline {
        agent {
            node {
                label 'ROBOSHOP'
            }
        }
        parameters {
            // 'dev' is first, so it's the default when this build is triggered
            // automatically by a push to main (no parameters supplied).
            choice(name: 'ENVIRONMENT', choices: ['dev', 'sit', 'uat', 'prod'], description: 'Environment to deploy to')
            string(name: 'COMMIT_ID', defaultValue: '', description: 'Commit SHA to promote (required for sit/uat/prod)')
            string(name: 'COMPONENT', defaultValue: 'catalogue', description: 'Component to deploy')
            string(name: 'PROJECT', defaultValue: 'roboshop', description: 'Project name')
            string(name: 'CR_NUMBER', defaultValue: '', description: 'Change Request number (required for prod deploy)')
            string(name: 'VERSION', defaultValue: '', description: 'Release version to tag the commit with on a successful prod deploy, e.g. v1.4.0 (required for prod deploy)')
            string(name: 'ISSUE_KEY', defaultValue: '', description: 'Jira ticket key this build should update (set automatically by the dev pipeline when it creates the ticket; leave blank for manual promotions with no Jira tracking)')
        }
        environment {
            def appVersion = ""
            def issueKey = ""
            // Resolved once in 'resolve-inputs': prefer the Jira webhook's env.* payload
            // (GenericTrigger only ever populates env.*, never params.*) and fall back to
            // params.* for manual "Build with Parameters" runs. Every stage below reads
            // these instead of params.* directly, so one build handles both trigger paths.
            def env_ENVIRONMENT = ""
            def env_COMMIT_ID = ""
            def env_COMPONENT = ""
            def env_PROJECT = ""
            def env_ISSUE_KEY = ""
            def env_VERSION = ""
            def env_CR_NUMBER = ""
            acc_id = "160885265516"
            project = configMap.get("project")
            component = configMap.get("component")
            org = "90s-org"
            JIRA_SITE = "roboshop-jira"
            jiraProjectKey = "DAWS90S"
        }
        options {
            disableConcurrentBuilds()
            timeout(time: 15, unit: 'MINUTES')
        }
        // Branch jobs under this multibranch project have their whole Configure page
        // locked (computed from the Jenkinsfile scan), so "Trigger builds remotely"
        // can't be set via the UI at all. Declaring the trigger here instead means it's
        // regenerated from code on every scan — Jira Automation calls this webhook
        // directly. The 'jira-secret' credential is a Secret text credential; its
        // value is whatever gets passed as ?token=... in the webhook URL.
        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'ENVIRONMENT', value: '$.ENVIRONMENT'],
                    [key: 'COMMIT_ID', value: '$.COMMIT_ID'],
                    [key: 'COMPONENT', value: '$.COMPONENT'],
                    [key: 'PROJECT', value: '$.PROJECT'],
                    [key: 'ISSUE_KEY', value: '$.ISSUE_KEY'],
                    [key: 'VERSION', value: '$.VERSION'],
                    [key: 'CR_NUMBER', value: '$.CR_NUMBER']
                ],
                tokenCredentialId: 'jira-secret',
                causeString: 'Triggered by Jira Automation',
                printContributedVariables: true,
                printPostContent: true
            )
        }
        stages {
            // Runs first, always. Resolves the real inputs for this build once, so
            // every later stage can just read these instead of choosing between
            // params.* and env.* itself. env.* wins when present (webhook path) —
            // params.ENVIRONMENT is a choice param and can never actually be blank,
            // so it can't be used as an "was this a webhook build" signal itself.
            stage('resolve-inputs') {
                steps {
                    script {
                        env_ENVIRONMENT = (env.ENVIRONMENT?.trim() ?: params.ENVIRONMENT)?.trim()
                        env_COMMIT_ID   = (env.COMMIT_ID?.trim() ?: params.COMMIT_ID)?.trim()
                        env_COMPONENT   = (env.COMPONENT?.trim() ?: params.COMPONENT)?.trim()
                        env_PROJECT     = (env.PROJECT?.trim() ?: params.PROJECT)?.trim()
                        env_ISSUE_KEY   = (env.ISSUE_KEY?.trim() ?: params.ISSUE_KEY)?.trim()
                        env_VERSION     = (env.VERSION?.trim() ?: params.VERSION)?.trim()
                        env_CR_NUMBER   = (env.CR_NUMBER?.trim() ?: params.CR_NUMBER)?.trim()
                        echo "Resolved inputs: ENVIRONMENT=${env_ENVIRONMENT} COMMIT_ID=${env_COMMIT_ID} COMPONENT=${env_COMPONENT} PROJECT=${env_PROJECT} ISSUE_KEY=${env_ISSUE_KEY}"
                    }
                }
            }
            // Re-verify the merged commit in dev: redeploy the image built on the
            // feature branch and re-run api-tests against it before it's considered good.
            stage('read-version'){
                when {
                    expression { env_ENVIRONMENT == 'dev' }
                }
                steps{
                    script {
                        def packageJson = readJSON file: 'package.json'
                        appVersion = packageJson.version
                        echo "The application version is: ${appVersion}"
                    }
                }
            }
            stage('dev-deploy') {
                when {
                    expression { env_ENVIRONMENT == 'dev' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws eks update-kubeconfig --name roboshop --region us-east-1

                                    helm upgrade --install ${component} ./helm \
                                        -f ./helm/values-dev.yaml \
                                        --namespace roboshop-dev \
                                        --create-namespace \
                                        --set deployment.imageVersion=${appVersion} \
                                        --wait --timeout 5m

                                    kubectl rollout status deployment/${component} -n roboshop-dev --timeout=120s
                                """
                            }
                            utils.updateCommitStatus('success', 'Deployed to roboshop-dev', 'dev-deploy')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Deploy to roboshop-dev failed', 'dev-deploy')
                            throw e
                        }
                    }
                }
            }
            stage('api-tests') {
                when {
                    expression { env_ENVIRONMENT == 'dev' }
                }
                steps {
                    script {
                        try {
                            build job: 'ROBOSHOP/catalogue-api-tests', parameters: [
                                string(name: 'NAMESPACE', value: 'roboshop-dev'),
                                string(name: 'COMMIT_ID', value: env.GIT_COMMIT)
                            ], wait: true, propagate: true
                            utils.updateCommitStatus('success', 'catalogue-api-tests passed', 'api-tests')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'catalogue-api-tests failed', 'api-tests')
                            throw e
                        }
                    }
                }
            }
            // Dev-deploy and api-tests passed against this commit — open the Jira ticket
            // that tracks it through SIT/UAT/PROD, carrying the commit and version so
            // nobody has to type them in by hand later. Jenkins does not trigger SIT
            // itself — the ticket sits at Trigger SIT until Jira's Automation rule
            // fires the webhook above.
            stage('create-jira-ticket') {
                when {
                    expression { env_ENVIRONMENT == 'dev' }
                }
                steps {
                    script {
                        issueKey = utils.createJiraTicket(jiraProjectKey, env.GIT_COMMIT, appVersion)
                        echo "Created Jira ticket ${issueKey} for ${env.GIT_COMMIT} / ${appVersion}"
                    }
                }
            }
            // Promote the same image that just passed dev/api-tests by retagging it
            // with the short commit SHA in ECR.
            stage('promote-image') {
                when {
                    expression { env_ENVIRONMENT == 'dev' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.us-east-1.amazonaws.com

                                    docker pull ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                                    docker tag ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion} ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${env.GIT_COMMIT}
                                    docker push ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${env.GIT_COMMIT}
                                """
                            }
                            utils.updateCommitStatus('success', "Promoted image as ${env.GIT_COMMIT}", 'promote-image')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Image promotion failed', 'promote-image')
                            throw e
                        }
                    }
                }
            }
            // Jira-driven promotion path: SIT/UAT/PROD are all started by a Jira
            // Automation rule hitting the webhook above, which resolve-inputs already
            // folded into env_*. Each stage further down the chain (sit-deploy,
            // sit-integration-tests, ...) gates the next environment, so the required
            // contexts grow with the target.
            stage('validate-commit-status') {
                when {
                    expression { env_ENVIRONMENT in ['sit', 'uat', 'prod'] }
                }
                steps {
                    script {
                        if (!env_COMMIT_ID?.trim()) {
                            error("COMMIT_ID is required when deploying to ${env_ENVIRONMENT}")
                        }

                        def requiredContexts = ['dev-deploy', 'api-tests']
                        if (env_ENVIRONMENT in ['uat', 'prod']) {
                            requiredContexts += ['sit-deploy', 'sit-integration-tests']
                        }
                        if (env_ENVIRONMENT == 'prod') {
                            requiredContexts += ['uat-deploy', 'uat-regression-tests']
                        }
                        utils.validateCommitStatus(env_COMMIT_ID.trim(), requiredContexts)
                    }
                }
            }
            stage('sit-deploy') {
                when {
                    expression { env_ENVIRONMENT == 'sit' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws eks update-kubeconfig --name roboshop --region us-east-1

                                    helm upgrade --install ${env_COMPONENT} ./helm \
                                        -f ./helm/values-sit.yaml \
                                        --namespace roboshop-sit \
                                        --create-namespace \
                                        --set deployment.imageVersion=${env_COMMIT_ID} \
                                        --wait --timeout 5m

                                    kubectl rollout status deployment/${env_COMPONENT} -n roboshop-sit --timeout=120s
                                """
                            }
                            utils.updateCommitStatus('success', "Deployed ${env_COMMIT_ID} to roboshop-sit", 'sit-deploy')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Deploy to roboshop-sit failed', 'sit-deploy')
                            if (env_ISSUE_KEY?.trim()) {
                                utils.safeTransitionJiraIssue(env_ISSUE_KEY.trim(), 'SIT FAILED')
                            }
                            throw e
                        }
                    }
                }
            }
            stage('sit-integration-tests') {
                when {
                    expression { env_ENVIRONMENT == 'sit' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh "aws eks update-kubeconfig --name roboshop --region us-east-1"

                                // The Jenkins agent can't resolve *.svc.cluster.local from
                                // roboshop-sit, but it's in the same VPC as the EKS pods —
                                // route by pod IP instead of relying on cluster DNS.
                                def catalogueIp = utils.getPodIP('roboshop-sit', 'catalogue')
                                def cartIp      = utils.getPodIP('roboshop-sit', 'cart')
                                def userIp      = utils.getPodIP('roboshop-sit', 'user')
                                def shippingIp  = utils.getPodIP('roboshop-sit', 'shipping')
                                def paymentIp   = utils.getPodIP('roboshop-sit', 'payment')

                                build job: 'ROBOSHOP/roboshop-integration-tests', parameters: [
                                    string(name: 'NAMESPACE', value: 'roboshop-sit'),
                                    string(name: 'CATALOGUE_URL', value: "http://${catalogueIp}:8080"),
                                    string(name: 'CART_URL', value: "http://${cartIp}:8080"),
                                    string(name: 'USER_URL', value: "http://${userIp}:8080"),
                                    string(name: 'SHIPPING_URL', value: "http://${shippingIp}:8080"),
                                    string(name: 'PAYMENT_URL', value: "http://${paymentIp}:8080")
                                ], wait: true, propagate: true
                            }
                            utils.updateCommitStatus('success', 'roboshop-integration-tests passed', 'sit-integration-tests')
                            if (env_ISSUE_KEY?.trim()) {
                                utils.safeTransitionJiraIssue(env_ISSUE_KEY.trim(), 'SIT Done')
                            }
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'roboshop-integration-tests failed', 'sit-integration-tests')
                            if (env_ISSUE_KEY?.trim()) {
                                utils.safeTransitionJiraIssue(env_ISSUE_KEY.trim(), 'SIT FAILED')
                            }
                            throw e
                        }
                    }
                }
            }
            stage('uat-deploy') {
                when {
                    expression { env_ENVIRONMENT == 'uat' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws eks update-kubeconfig --name roboshop --region us-east-1

                                    helm upgrade --install ${env_COMPONENT} ./helm \
                                        -f ./helm/values-uat.yaml \
                                        --namespace roboshop-uat \
                                        --create-namespace \
                                        --set deployment.imageVersion=${env_COMMIT_ID} \
                                        --wait --timeout 5m

                                    kubectl rollout status deployment/${env_COMPONENT} -n roboshop-uat --timeout=120s
                                """
                            }
                            utils.updateCommitStatus('success', "Deployed ${env_COMMIT_ID} to roboshop-uat", 'uat-deploy')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Deploy to roboshop-uat failed', 'uat-deploy')
                            if (env_ISSUE_KEY?.trim()) {
                                utils.safeTransitionJiraIssue(env_ISSUE_KEY.trim(), 'UAT FAILED')
                            }
                            throw e
                        }
                    }
                }
            }
            stage('uat-regression-tests') {
                when {
                    expression { env_ENVIRONMENT == 'uat' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh "aws eks update-kubeconfig --name roboshop --region us-east-1"

                                // The Jenkins agent can't resolve *.svc.cluster.local from
                                // roboshop-sit, but it's in the same VPC as the EKS pods —
                                // route by pod IP instead of relying on cluster DNS.
                                def catalogueIp = utils.getPodIP('roboshop-uat', 'catalogue')
                                def cartIp      = utils.getPodIP('roboshop-uat', 'cart')
                                def userIp      = utils.getPodIP('roboshop-uat', 'user')
                                def shippingIp  = utils.getPodIP('roboshop-uat', 'shipping')
                                def paymentIp   = utils.getPodIP('roboshop-uat', 'payment')

                                build job: 'ROBOSHOP/roboshop-regression-tests', parameters: [
                                    string(name: 'NAMESPACE', value: 'roboshop-uat'),
                                    string(name: 'CATALOGUE_URL', value: "http://${catalogueIp}:8080"),
                                    string(name: 'CART_URL', value: "http://${cartIp}:8080"),
                                    string(name: 'USER_URL', value: "http://${userIp}:8080"),
                                    string(name: 'SHIPPING_URL', value: "http://${shippingIp}:8080"),
                                    string(name: 'PAYMENT_URL', value: "http://${paymentIp}:8080")
                                ], wait: true, propagate: true
                            }
                            utils.updateCommitStatus('success', 'roboshop-regression-tests passed', 'uat-regression-tests')
                            if (env_ISSUE_KEY?.trim()) {
                                utils.safeTransitionJiraIssue(env_ISSUE_KEY.trim(), 'UAT Done')
                            }
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'roboshop-regression-tests failed', 'uat-regression-tests')
                            if (env_ISSUE_KEY?.trim()) {
                                utils.safeTransitionJiraIssue(env_ISSUE_KEY.trim(), 'UAT FAILED')
                            }
                            throw e
                        }
                    }
                }
            }
            // CR gate: number + version must be supplied, deploy must fall inside the
            // approved window, and a human has to click approve. Runs with its own
            // timeout so waiting on a person doesn't get killed by the pipeline's
            // overall 15-minute budget.
            stage('change-request-check') {
                when {
                    expression { env_ENVIRONMENT == 'prod' }
                }
                options {
                    timeout(time: 4, unit: 'HOURS')
                }
                steps {
                    script {
                        if (!env_CR_NUMBER?.trim()) {
                            error("CR_NUMBER is required for a prod deploy")
                        }
                        if (!env_VERSION?.trim()) {
                            error("VERSION is required for a prod deploy")
                        }

                        // Dummy deployment-window check — placeholder until this is wired
                        // up to a real CR system. Blocks weekend prod deploys for now.
                        def dayOfWeek = sh(script: 'date +%u', returnStdout: true).trim().toInteger()
                        if (dayOfWeek >= 6) {
                            error("CR ${env_CR_NUMBER}: outside the approved deployment window (no weekend prod deploys) — dummy check, replace with a real CR window lookup")
                        }
                        echo "CR ${env_CR_NUMBER}: within deployment window"

                        input message: "Approve prod deploy of ${env_COMPONENT}@${env_COMMIT_ID} as ${env_VERSION} under CR ${env_CR_NUMBER}?", ok: 'Approve'
                    }
                }
            }
            stage('prod-deploy') {
                when {
                    expression { env_ENVIRONMENT == 'prod' }
                }
                steps {
                    script {
                        withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                            sh "aws eks update-kubeconfig --name roboshop --region us-east-1"

                            // Only attempt a rollback if there's a prior successful release
                            // to roll back to — a failed first-ever deploy has nothing behind it.
                            def releaseExists = sh(
                                script: "helm status ${env_COMPONENT} -n roboshop-prod > /dev/null 2>&1",
                                returnStatus: true
                            ) == 0

                            try {
                                sh """
                                    helm upgrade --install ${env_COMPONENT} ./helm \
                                        -f ./helm/values-prod.yaml \
                                        --namespace roboshop-prod \
                                        --create-namespace \
                                        --set deployment.imageVersion=${env_COMMIT_ID} \
                                        --wait --timeout 5m

                                    kubectl rollout status deployment/${env_COMPONENT} -n roboshop-prod --timeout=120s
                                """
                                utils.updateCommitStatus('success', "Deployed ${env_COMMIT_ID} to roboshop-prod (CR ${env_CR_NUMBER})", 'prod-deploy')
                            }
                            catch (Exception e) {
                                if (releaseExists) {
                                    echo "prod-deploy failed on an existing release — rolling back ${env_COMPONENT} in roboshop-prod"
                                    sh "helm rollback ${env_COMPONENT} 0 -n roboshop-prod --wait --timeout 5m"
                                } else {
                                    echo "prod-deploy failed on the first-ever release of ${env_COMPONENT} — nothing to roll back to"
                                }
                                utils.updateCommitStatus('failure', 'Deploy to roboshop-prod failed', 'prod-deploy')
                                if (env_ISSUE_KEY?.trim()) {
                                    utils.safeTransitionJiraIssue(env_ISSUE_KEY.trim(), 'PROD FAILED')
                                }
                                throw e
                            }
                        }
                    }
                }
            }
            // Only reached if prod-deploy succeeded — declarative pipeline stops
            // running further stages once one fails.
            stage('tag-release') {
                when {
                    expression { env_ENVIRONMENT == 'prod' }
                }
                steps {
                    script {
                        utils.tagCommit(env_COMMIT_ID.trim(), env_VERSION.trim())
                        echo "Tagged ${env_COMMIT_ID} as ${env_VERSION} (CR ${env_CR_NUMBER})"
                        if (env_ISSUE_KEY?.trim()) {
                            utils.safeTransitionJiraIssue(env_ISSUE_KEY.trim(), 'Completed')
                        }
                    }
                }
            }
        }

        post {
            success {
                /* slackSend(
                    channel: '#test-ci',
                    color: 'good',
                    tokenCredentialId: 'slack-token',
                    message: "✅ *${env_COMPONENT ?: component}* ${env_ENVIRONMENT} deploy succeeded — commit `${env_COMMIT_ID ?: env.GIT_COMMIT}` (<${env.BUILD_URL}console|console>)"
                ) */
                echo "success"
            }
            failure {
                /* slackSend(
                    channel: '#test-ci',
                    color: 'danger',
                    tokenCredentialId: 'slack-token',
                    message: "❌ *${env_COMPONENT ?: component}* ${env_ENVIRONMENT} deploy failed — commit `${env_COMMIT_ID ?: env.GIT_COMMIT}` (<${env.BUILD_URL}console|console>)"
                ) */
                echo "failure"
            }
        }
    }
}
