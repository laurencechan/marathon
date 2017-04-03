// Libraries of methods for testing Marathon in jenkins

// Add a prefix to all of the JUnit result files listed
// This is particularly useful for tagging things like "Unstable-${TestName}"
def setJUnitPrefix(prefix, files) {
  // add prefix to qualified classname
  sh "bash -c 'shopt -s globstar && sed -i \"s/\\(<testcase .*classname=['\\\"]\\)\\([a-z]\\)/\\1${prefix.toUpperCase()}.\\2/g\" $files'"
  return this
}

// Run the given phabricator method (e.g. arc call-conduit <method>) with
// the given jq arguments wrapped in a json object.
// e.g. phabricator("differential.revision.edit", """ transactions: [{type: "comment", "value": "Some comment"}], objectIdentifier: "D1" """)
def phabricator(method, args) {
  sh "jq -n '{ $args }' | arc call-conduit $method || true"
  return this
}

// Report all the test results for the given PHID with the given status to Harbormaster.
// PHID is expected to be set as an environment variable
def phabricator_test_results(status) {
  sh """jq -s add '{buildTargetPHID: "$PHID", type: "$status", unit: [.[] * .[]] }' target/phabricator-test-reports/* | arc call-conduit harbormaster.sendmessage """
  return this
}

// Convert the test coverage into a "fake" unit test result so that
// phabricator_test_results can consume it and report the coverage.
def phabricator_convert_test_coverage() {
  sh """sudo sh -c "/usr/local/bin/amm bin/convert_test_coverage.scala" """
  return this
}

// Publish the test coverage information into the build.
// Currently, none of the methods for reporting this actually work
def publish_test_coverage(name) {
  //currentBuild.description += "<h3>$name</h3>"
  //currentBuild.description += readFile("target/scala-2.11/scoverage-report/index.html")
  //publishHtml([allowMissing: true, alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'target/scoverage-report', reportFiles: 'index.html', reportName: "Test Coverage"])
  return this
}

// Applies the phabricator diff and posts messages to phabricator
// that the build is in progress, the revision is rejected and
// the harbormaster build has the given URL.
// Ephid: the harbormaster phid to update.
// build_url: The build URL of the jenkins build
// revision_id: the revision id being built, e.g. D123
// diff_id: The diff id to apply (e.g. 2458)
def phabricator_apply_diff(phid, build_url, revision_id, diff_id) {
  phabricator("harbormaster.createartifact", """buildTargetPHID: "$phid", artifactType: "uri", artifactKey: "$build_url", artifactData: { uri: "$build_url", name: "Velocity Results", "ui.external": true }""")
  phabricator("differential.revision.edit", """transactions: [{type: "reject", value: true}], objectIdentifier: "D$revision_id" """)
  phabricator("harbormaster.sendmessage", """ buildTargetPHID: "$phid", type: "work" """)
  sh "arc patch --diff $diff_id"
}

// installs mesos at the revision listed in the build.
def install_mesos() {
  sh """if grep -q MesosDebian \$WORKSPACE/project/Dependencies.scala; then
          MESOS_VERSION=\$(sed -n 's/^.*MesosDebian = "\\(.*\\)"/\\1/p' <\$WORKSPACE/project/Dependencies.scala)
        else
          MESOS_VERSION=\$(sed -n 's/^.*mesos=\\(.*\\)&&.*/\\1/p' <\$WORKSPACE/Dockerfile)
        fi
        sudo apt-get install -y --force-yes --no-install-recommends mesos=\$MESOS_VERSION
      """
  return this
}

// Kill stale processes left-over from old builds.
def kill_junk() {
  sh "bin/kill-stale-test-processes"
}

// Install job-level dependencies that aren't specific to the build and
// can be required as part of checkout and should be applied before knowing
// the revision's information. e.g. JQ is required to post to phabricator.
def install_dependencies() {
  // JQ is broken in the image
  sh "curl -L https://github.com/stedolan/jq/releases/download/jq-1.5/jq-linux64 > /tmp/jq && sudo mv /tmp/jq /usr/bin/jq && sudo chmod +x /usr/bin/jq"
  // install ammonite (scala shell)
  sh """mkdir -p ~/.ammonite && curl -L -o ~/.ammonite/predef.sc https://git.io/v6r5y && mkdir -p ~/.ammonite && curl -L -o ~/.ammonite/predef.sc https://git.io/v6r5y"""
  sh """sudo curl -L -o /usr/local/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/0.8.2/2.12-0.8.2 && sudo chmod +x /usr/local/bin/amm"""
  return this
}

def setBuildInfo(displayName, description) {
  currentBuild.displayName = displayName
  currentBuild.description = description
  return this
}

def checkout_marathon_master() {
  git changelog: false, credentialsId: '4ff09dce-407b-41d3-847a-9e6609dd91b8', poll: false, url: 'git@github.com:mesosphere/marathon.git'
  sh "git branch | grep -v master | xargs git branch -D"
  sh "sudo git clean -fdx"
  return this
}

// run through compile/lint/docs. Fail if there were format changes after this.
def compile() {
  withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
    //sh "sudo -E sbt -Dsbt.log.format=false clean scapegoat doc coverage test:compile"
    sh """if git diff --quiet; then echo 'No format issues detected'; else echo 'Patch has Format Issues'; exit 1; fi"""
  }
}

def test() {
  try {
    timeout(time: 30, unit: 'MINUTES') {
      withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
        sh """sudo -E sbt -Dsbt.log.format=false coverage test coverageReport"""
      }
    }
  } finally {
    junit allowEmptyResults: true, testResults: 'target/test-reports/**/*.xml'
  }
}

def integration_test() {
  try {
    timeout(time: 30, unit: 'MINUTES') {
      withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
        sh """sudo -E sbt -Dsbt.log.format=false '; clean; coverage; integration:test; set coverageFailOnMinimum := false; coverageReport; mesos-simulation/integration:test' """
      }
    }
  } finally {
    junit allowEmptyResults: true, testResults: 'target/test-reports/*integration/**/*.xml'
  }
}

def has_unstable_tests() {
  "git grep \"@UnstableTest\" | wc -l".execute().text != "0"
}
def unstable_test() {
  try {
    timeout(time: 60, unit: 'MINUTES') {
      withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
        sh "sudo -E sbt -Dsbt.log.format=false clean coverage unstable:test unstable-integration:test coverageReport"
      }
    }
  } finally {
    setJUnitPrefix("Unstable", "target/test-reports/unstable-integration/**/*.xml")
    setJUnitPrefix("Unstable", "target/test-reports/unstable/**/*.xml")
    junit allowEmptyResults: true, testResults: 'target/test-reports/unstable-integration/**/*.xml'
    junit allowEmptyResults: true, testResults: 'target/test-reports/unstable/**/*.xml'
  }
}

def assembly() {
  sh "sudo -E sbt assembly"
  sh "sudo bin/build-distribution"
}

def package_binaries() {
  parallel (
    "Tar Binaries": {
      sh """sudo tar -czv -f "target/marathon-${gitCommit}.tgz" \
              Dockerfile \
              README.md \
              LICENSE \
              bin \
              examples \
              docs \
              target/scala-2.*/marathon-assembly-*.jar
         """
    },
    "Create Debian and Red Hat Package": {
      sh "sudo rm -rf marathon-pkg && git clone https://github.com/mesosphere/marathon-pkg.git marathon-pkg"
      dir("marathon-pkg") {
         // marathon-pkg has marathon as a git module. We've already
         // checked it out. So let's just symlink.
         sh "sudo rm -rf marathon && ln -s ../ marathon"
         sh "sudo make all"
      }
    },
    "Build Docker Image": {
      // target is in .dockerignore so we just copy the jar before.
      sh "cp target/*/marathon-assembly-*.jar ."
      mesosVersion = sh(returnStdout: true, script: "sed -n 's/^.*MesosDebian = \"\\(.*\\)\"/\\1/p' <./project/Dependencies.scala").trim()
      docker.build("mesosphere/marathon:${gitCommit}", "--build-arg MESOS_VERSION=${mesosVersion} .")
    }
   )
}
