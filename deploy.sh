#!/bin/bash
echo 'Upload new image to docker hub !'
echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin
docker push egorebasko/stuff-doer
