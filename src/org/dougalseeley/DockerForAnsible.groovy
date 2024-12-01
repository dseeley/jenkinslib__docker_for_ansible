#!groovy

package org.dougalseeley

import groovy.json.JsonSlurper

class DockerForAnsible {
    def script // Holds the pipeline script context

    DockerForAnsible(def script) {
        this.script = script
    }

    String get_parent_network() {
        String docker_parent_net_str = ""
        if (script.sh(script: 'grep -sq "docker\\|lxc" /proc/1/cgroup', returnStatus: true) == 0) {
            script.println("Running in docker.  Getting network to pass to docker-in-docker containers...")
            def docker_parent_net_id = script.sh(script: 'docker inspect  $(grep -oP \'(?<=docker-)[a-f0-9]+(?=\\.scope)\' /proc/1/cgroup | head -1) -f "{{ range .NetworkSettings.Networks }}{{println .NetworkID}}{{end}}" | head -n 1', returnStdout: true).trim()
            docker_parent_net_str = "--network ${docker_parent_net_id}"
            script.println("docker_parent_net_str: ${docker_parent_net_str}")
        } else {
            script.println("Not running in docker.  Not getting network to pass to docker-in-docker containers...")
        }
        return docker_parent_net_str
    }

    def build_image(Map args) {
        String image_name = args && args.image_name ? args.image_name : ""
        String build_opts = args && args.build_opts ? args.build_opts : ""
        String ansible_version = args && args.ansible_version ? args.ansible_version : ""

        if (ansible_version == "") {
            def pypi_ansible = ["curl", "-s", "-H", "Accept: application/json", "-H", "Content-type: application/json", "GET", "https://pypi.org/pypi/ansible/json"].execute().text
            ansible_version = new JsonSlurper().parseText(pypi_ansible).info.version
        }

        if (image_name == "") {
            image_name = "ubuntu_ansible_v${ansible_version}"
        } else {
            image_name = image_name.toLowerCase()
        }

        script.lock("IMAGEBUILDLOCK__${image_name}__${script.env.NODE_NAME}") {
            def jenkins_username = script.sh(script: 'whoami', returnStdout: true).trim()
            def jenkins_uid = script.sh(script: "id -u ${jenkins_username}", returnStdout: true).trim()
            def jenkins_gid = script.sh(script: "id -g ${jenkins_username}", returnStdout: true).trim()

            def dockerfile = """
                FROM ubuntu:24.04
                ARG DEBIAN_FRONTEND=noninteractive
                ENV TZ=UTC
                SHELL ["/bin/bash", "-c"]

                RUN groupadd -g ${jenkins_gid} ${jenkins_username} && useradd -m -u ${jenkins_uid} -g ${jenkins_gid} -s /bin/bash ${jenkins_username}
                
                RUN ln -snf /usr/share/zoneinfo/\$TZ /etc/localtime && echo \$TZ > /etc/timezone \
                    && apt-get update && apt-get install -y git iproute2 python3 python3-pip \
                    && pip3 --no-cache-dir install --break-system-packages ansible==${ansible_version} \
                    && pip3 --no-cache-dir install --break-system-packages -r \$(pip3 --no-cache-dir show ansible | grep ^Location | sed -r 's/^Location: (.*)/\\1/')/ansible_collections/azure/azcollection/requirements*.txt \
                    && apt-get install -y python3-jmespath python3-jinja2 python3-boto3 python3-netaddr python3-paramiko python3-libvirt python3-lxml python3-xmltodict python3-pycdlib python3-google-auth python3-dev python3-setuptools python3-wheel
            """.stripIndent()

            script.writeFile(file: "Dockerfile_${image_name}", text: dockerfile, encoding: "UTF-8")
            def custom_build = script.docker.build(image_name, build_opts + " --network host -f Dockerfile_${image_name} .")
            return custom_build
        }
    }
}