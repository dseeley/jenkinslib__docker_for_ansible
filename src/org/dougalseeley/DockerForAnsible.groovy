#!groovy

package org.dougalseeley

def create_custom_image(image_name, build_opts = "") {
    // Create a lock to prevent building the same image in parallel
    lock('IMAGEBUILDLOCK__' + image_name + '__' + env.NODE_NAME) {
        def jenkins_username = sh(script: 'whoami', returnStdout: true).trim()
        def jenkins_uid = sh(script: "id -u  ${jenkins_username}", returnStdout: true).trim()
        def jenkins_gid = sh(script: "id -g  ${jenkins_username}", returnStdout: true).trim()

        def dockerfile = """
            FROM ubuntu:24.04
            ARG DEBIAN_FRONTEND=noninteractive
            ENV JENKINS_HOME=${env.JENKINS_HOME}
            ENV HOME=${env.JENKINS_HOME}
            ENV TZ=Europe/London
            SHELL ["/bin/bash", "-c"]

            RUN groupadd -g ${jenkins_gid} ${jenkins_username} && useradd -m -u ${jenkins_uid} -g ${jenkins_gid} -s /bin/bash ${jenkins_username}
            RUN apt-get update \
                && apt-get install -y git iproute2 python3-pip python3-jmespath python3-jinja2 python3-boto3 python3-netaddr python3-paramiko python3-libvirt python3-lxml python3-xmltodict python3-pycdlib python3-google-auth python3-dev python3-setuptools python3-wheel \
                && pip3 --no-cache-dir install --break-system-packages ansible==${params.ANSIBLE_VERSION}

            ### Install the azcollection/requirements.txt dependencies (in the default python library location)
            RUN pip3 --no-cache-dir install --break-system-packages -r \$(pip3 --no-cache-dir show ansible | grep ^Location | sed -r 's/^Location: (.*)/\\1/')/ansible_collections/azure/azcollection/requirements*.txt
            """.stripIndent()

        writeFile(file: "Dockerfile_${image_name}", text: dockerfile, encoding: "UTF-8")
        custom_build = docker.build(image_name, build_opts + "--network host -f Dockerfile_${image_name} .")

        return (custom_build)
    }
}