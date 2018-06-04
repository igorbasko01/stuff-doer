FROM openjdk:8
COPY ./target/stuff-doer-1.0-SNAPSHOT-shaded.jar /usr/local/stuff-doer/stuff-doer-1.0-SNAPSHOT-shaded.jar
COPY ./src/main/resources/application.conf /usr/local/stuff-doer/application.conf
COPY ./src/main/resources/html/* /usr/local/stuff-doer/src/main/resources/html/
COPY ./src/main/resources/mp3/* /usr/local/stuff-doer/src/main/resources/mp3/
WORKDIR /usr/local/stuff-doer
EXPOSE 9080
CMD ["java", "-Dconfig.file=/usr/local/stuff-doer/application.conf", "-cp" ,"./stuff-doer-1.0-SNAPSHOT-shaded.jar", "main.Main"]