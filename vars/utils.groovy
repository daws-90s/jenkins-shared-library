def updateCommitStatus(String state, String description, String context = 'Jenkins CI') {
    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        def repoUrl = sh(script: 'git remote get-url origin', returnStdout: true).trim()
        def repoPath = repoUrl.replaceAll(/.*github\.com[\/:]/, '').replaceAll(/\.git$/, '')

        // GitHub's Commit Status API only accepts lowercase state values
        // (error|failure|pending|success); anything else gets a 422.
        withEnv([
            "COMMIT_STATE=${state.toLowerCase()}",
            "COMMIT_DESC=${description}",
            "COMMIT_CONTEXT=${context}",
            "REPO_PATH=${repoPath}",
            "COMMIT_SHA=${env.GIT_COMMIT}",
            "BUILD_LINK=${env.BUILD_URL}"
        ]) {
            sh '''
                HTTP_STATUS=$(jq -n \
                    --arg state   "$COMMIT_STATE" \
                    --arg url     "$BUILD_LINK" \
                    --arg desc    "$COMMIT_DESC" \
                    --arg context "$COMMIT_CONTEXT" \
                    '{state: $state, target_url: $url, description: $desc, context: $context}' \
                | curl -s \
                       -o commit-status-response.json \
                       -w "%{http_code}" \
                       -X POST \
                       -H "Authorization: Bearer $GITHUB_TOKEN" \
                       -H "Accept: application/vnd.github+json" \
                       -H "Content-Type: application/json" \
                       -H "X-GitHub-Api-Version: 2022-11-28" \
                       --data @- \
                       "https://api.github.com/repos/$REPO_PATH/statuses/$COMMIT_SHA")

                if [ "$HTTP_STATUS" -lt 200 ] || [ "$HTTP_STATUS" -ge 300 ]; then
                    echo "GitHub commit status update failed (HTTP $HTTP_STATUS) for context '$COMMIT_CONTEXT':"
                    cat commit-status-response.json
                    rm -f commit-status-response.json
                    exit 1
                fi
                rm -f commit-status-response.json
            '''
        }
    }
}