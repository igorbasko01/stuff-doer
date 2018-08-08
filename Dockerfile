FROM openjdk:8
COPY ./target/stuff-doer-1.0-SNAPSHOT-shaded.jar /usr/local/stuff-doer/stuff-doer-1.0-SNAPSHOT-shaded.jar
COPY ./src/main/resources/application.conf /usr/local/stuff-doer/application.conf
COPY ./target/scala-2.11/stuff-doer-assembly-1.0-SNAPSHOT.jar /usr/local/stuff-doer/stuff-doer-1.0-SNAPSHOT-shaded.jar
COPY ./src/main/resources/logback.xml /usr/local/stuff-doer/logback.xml
COPY ./src/main/resources/html/* /usr/local/stuff-doer/src/main/resources/html/
COPY ./src/main/resources/mp3/* /usr/local/stuff-doer/src/main/resources/mp3/
WORKDIR /usr/local/stuff-doer
EXPOSE 9080
CMD ["java", "-Dconfig.file=/root/stuff-doer/config/application.conf", \
"-Dlogback.configurationFile=/usr/local/stuff-doer/logback.xml", \
"-cp" ,"./stuff-doer-1.0-SNAPSHOT-shaded.jar", "main.Main"]
