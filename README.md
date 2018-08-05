[![Build Status](https://travis-ci.org/igorbasko01/stuff-doer.svg?branch=master)](https://travis-ci.org/igorbasko01/stuff-doer)
# What Is It?
A task management program, that decides for the user on what task to work now.
When the user adds a task to the system, he only controls the priority 
of the task, while the algorithm of the program will try to avoid starvation
and provide enough time to work on each task according to the priority.
# How to build and run
## Build
Just clone/fork and build using Maven: `mvn package`.

It will create a JAR that contains all the needed parts: DB, Backend (Akka), Frontend (Web UI). 
## Run (Using docker)

