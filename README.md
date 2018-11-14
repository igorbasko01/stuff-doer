[![Build Status](https://travis-ci.org/igorbasko01/stuff-doer.svg?branch=master)](https://travis-ci.org/igorbasko01/stuff-doer)
# What Is It? 
A task management program, that decides for the user on what task to work now.
When the user adds a task to the system, he only controls the priority 
of the task, while the algorithm of the program will try to avoid starvation
and provide enough time to work on each task according to the priority.
# How to build and run
## Build
Just clone/fork and build using Maven: `sbt assembly`.

It will create a JAR that contains all the needed parts: DB, Backend (Akka), Frontend (Web UI). 
## Run (Using docker)
Build a docker image using the following command (Run it from the root of the repo): `docker build -t stuff-doer .`

To run the docker image, just use the following command: `docker run -p 80:9080 stuff-doer`

If you want to store the tasks between container executions, you need to create a persistent volume, and use it when starting a container. Follow these instructions:

Create a docker volume to use with the image so it will store all the tasks: `docker volume create test`

Run the docker image, with the following command: `docker run -p 80:9080 --mount src=test,target=/root stuff-doer`

That's all . It should start a server, and you can access it through: `http://localhost`
