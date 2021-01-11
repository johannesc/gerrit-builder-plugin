# gerrit-builder-plugin
Jenkins plugin to build Gerrit changes with dependencies

# Current Status
This plugin is written as a "proof of concept" and should not be used in a production system.

# Why this?
All otheer (to me) known plugins focus on testing a single commit, usually in one repository. This plugin focus on testing all affected projects and utilizing the "automatic submodule update" and "submit whole topic" features that Gerrit offers.

Consider this tree of changes in Gerrit where "main" is a project with sub as a submodule:

```
main     sub
  C
  |
  B       S2     Marked with "TOPIC1"
  |       |
  A       S1
```

This plugin will group the commmit into 4 "Submit Groups": [A, S1, B+S2, C]. Each of these "Submit Groups" will be tested and given a review score individually.

The following 7 builds will be made:
```
main-master-A
main-master-S1
sub-master-S1
sub-master-S2-B
main-master-S2-B
main-master-C
sub-master-C (not needed, but the code currently cannot understand this)
```

All of this is automatically figured out the the plugin with the information in Gerrit.

# Configuring Gerrit

## Setup webhook
Make sure that a webhook is configured by creating a "webhooks.config" in the "All-Projects" projects refs/meta/config branch:

E.g. something like:

```
[remote "jenkins"]
        url = http://localhost:8080/jenkins/gerrit-builder-webhook/
```

This can also be done with curl:

```
curl -H "Content-Type: application/json" --request PUT --user <user>:<http-password> http://gerrit-server>:<gerrit-port>/a/config/server/webhooks~projects/All-Projects/remotes/jenkins --data '{"url": "http://<jenkins-server>:<jenkins-port>/gerrit-builder-webhook/"}'
```

NOTE: Make sure that the URL is correct, depending if you run using maven or installed your plugin the URL might be http://localhost:8080/jenkins/gerrit-builder-webhook/ or http://localhost:8080/gerrit-builder-webhook/. Test the URL using your browser, if the URL ir correct you should get "HTTP ERROR 400 Invalid HTTP method, use POST". If you get "HTTP ERROR 404 Not Found" your URL is incorrect.


## Make sure that you have a "Verified" label

https://gerrit-review.googlesource.com/Documentation/config-labels.html#label_Verified

## Useful configurations
In $GERRIT_HOME/etc/gerrit.config enable change.submitWholeTopic:

```
[change]
        submitWholeTopic = true
```

# Start Jenkins
mvn hpi:run

# Configuring Jenkins
## Add Credentials
Go to "Jenkins->Manage Jenkins->Manage Credentials->Jenkins->Global credentials (unrestricted)->Add Credentials" and fill in the credentials and press "OK":

![Jenkins Credentials](doc/images/credentials.png?raw=true "Jenkins Credentials")

## Configuring Gerrit Builder Plugin
Go to "Jenkins->Manage Jenkins->Configure System" and fill in the information at "Gerrit Builder Plugin":

![Gerrit Builder Config](doc/images/gerrit-builder-config.png?raw=true "Gerrit Builder Config")

(Insecure HTTPS is not yet implemented)

## Add Jenkins Projects

* Make sure that the "Blue ocean" plugin is installed
* For each project in Gerrit that should be tested a corresponding Jenkins Pipeline project should be created. Got to "Jenkins->New Item" and select "Pipeline" and a name, then press "OK":

![Jenkins New Item](doc/images/new-jenkins-item.png?raw=true "Jenkins New Item")

* Check "This project is parameterized" and add a "String Parameter" for "GERRIT_PROJECT", "GERRIT_CHANGE_NUMBER", "GERRIT_PATCHSET_NUMBER" and "GERRIT_BRANCH":

![Jenkins Parameters](doc/images/new-jenkins-item-parameterized.png?raw=true "Jenkins Parameters")

* Under "Pipeline" select "Pipeline script from SCM" and then "Git". Fill in correct URL and select "Credentials".
* Click "Add" next to "Additional Behaviours" and "Advanced sub-modules behaviours", check "Recursively update submodules" and "Use credentials from default remote of parent repository".
* Click "Add" next to "Additional Behaviours" and "Trigger build and download changes from Gerrit". Uncheck "Lightweight checkout" (might not be needed),

![Jenkins Pipeline](doc/images/new-jenkins-item-pipeline.png?raw=true "Jenkins Pipeline")

* Click "Save"
* Press "Build with Parameters" and write some valid parameters and press "Build" (due to https://issues.jenkins.io/browse/JENKINS-45720)

![Jenkins Initial Build](doc/images/jenkins-initial-build.png?raw=true "Jenkins Initial Build")

Remember to do this fo all projects.

# Test

Create a new PatchSet in Gerrit. Jenkins should now trigger a build.

# Problems?

* Check the gerrit log
* Make sure the webhook is correct
