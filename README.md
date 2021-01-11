# gerrit-builder-plugin
Jenkins plugin to build Gerrit changes with dependencies

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
