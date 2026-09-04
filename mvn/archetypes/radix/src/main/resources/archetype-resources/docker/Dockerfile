# Build from the generated project root after `mvn clean package`.
FROM eclipse-temurin:21-jdk-jammy AS base
ENV OODT_HOME=/oodt \
    FILEMGR_HOME=/oodt/filemgr \
    WORKFLOW_HOME=/oodt/workflow \
    RESMGR_HOME=/oodt/resmgr \
    CRAWLER_HOME=/oodt/crawler \
    PCS_HOME=/oodt/pcs
WORKDIR /oodt
RUN mkdir -p data/incoming data/archive data/backup data/failure

FROM base AS filemgr
ADD filemgr/target/*-bin.tar.gz /oodt/
WORKDIR /oodt/filemgr
EXPOSE 9000
CMD ["java", "-cp", "lib/*", "-Dorg.apache.oodt.cas.filemgr.properties=etc/filemgr.properties", "org.apache.oodt.cas.filemgr.system.FileManagerServerMain", "--portNum", "9000"]

FROM base AS workflow
ADD workflow/target/*-bin.tar.gz /oodt/
WORKDIR /oodt/workflow
EXPOSE 9002
CMD ["java", "-cp", "lib/*", "-Dorg.apache.oodt.cas.workflow.properties=etc/workflow.properties", "org.apache.oodt.cas.workflow.system.WorkflowManagerStarter", "--portNum", "9002"]

FROM base AS resmgr
ADD resmgr/target/*-bin.tar.gz /oodt/
RUN mkdir -p /oodt/resmgr/policy/nodes
WORKDIR /oodt/resmgr
EXPOSE 9001
CMD ["java", "-cp", "lib/*", "-Dorg.apache.oodt.cas.resource.properties=etc/resource.properties", "org.apache.oodt.cas.resource.system.ResourceManagerMain", "--portNum", "9001"]

FROM base AS crawler
ADD crawler/target/*-bin.tar.gz /oodt/
WORKDIR /oodt/crawler/bin
CMD ["java", "-cp", "../lib/*", "-Dorg.apache.oodt.cas.crawl.bean.repo=../policy/crawler-config.xml", "-Dorg.apache.oodt.cas.cli.action.spring.config=../policy/cmd-line-actions.xml", "-Dorg.apache.oodt.cas.cli.option.spring.config=../policy/cmd-line-options.xml", "org.apache.oodt.cas.crawl.CrawlerLauncher", "--operation", "--launchAutoCrawler", "--filemgrUrl", "http://filemgr:9000", "--clientTransferer", "org.apache.oodt.cas.filemgr.datatransfer.LocalDataTransferFactory", "--productPath", "/oodt/data/incoming", "--mimeExtractorRepo", "../policy/extractor/mime-extractor-map.xml", "--daemonPort", "9020", "--daemonWait", "5", "--actionIds", "MoveDataFileToBackupDir", "MoveDataFileToFailureDir", "--successDir", "/oodt/data/backup", "--failureDir", "/oodt/data/failure"]

FROM tomcat:9.0.121-jdk21-temurin-noble AS opsui
ENV OODT_HOME=/oodt \
    FILEMGR_HOME=/oodt/filemgr \
    WORKFLOW_HOME=/oodt/workflow \
    RESMGR_HOME=/oodt/resmgr \
    PCS_HOME=/oodt/pcs \
    FILEMGR_URL=http://filemgr:9000 \
    WORKFLOW_URL=http://workflow:9002 \
    RESMGR_URL=http://resmgr:9001
ADD pcs/target/*-bin.tar.gz /oodt/
RUN sed -i 's|<scalar name="Hostname">localhost</scalar>|<scalar name="Hostname">crawler</scalar>|' /oodt/pcs/policy/pcs-crawlers.xml
COPY webapps/opsui/target/*.war /usr/local/tomcat/webapps/opsui.war
COPY webapps/pcs-services/target/*.war /usr/local/tomcat/webapps/pcs.war
COPY filemgr/src/main/resources/etc/ /oodt/filemgr/etc/
COPY workflow/src/main/resources/etc/ /oodt/workflow/etc/
COPY resmgr/src/main/resources/etc/ /oodt/resmgr/etc/

FROM base AS smoke
ADD filemgr/target/*-bin.tar.gz /oodt/
ADD workflow/target/*-bin.tar.gz /oodt/
ADD resmgr/target/*-bin.tar.gz /oodt/
COPY docker/SmokeTest.java /oodt/docker/SmokeTest.java
RUN javac -cp '/oodt/filemgr/lib/*:/oodt/workflow/lib/*:/oodt/resmgr/lib/*' /oodt/docker/SmokeTest.java
ENTRYPOINT ["java", "-cp", "/oodt/docker:/oodt/filemgr/lib/*:/oodt/workflow/lib/*:/oodt/resmgr/lib/*", "SmokeTest"]
CMD ["smoke"]
