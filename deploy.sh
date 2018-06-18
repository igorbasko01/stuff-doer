#!/bin/bash
echo 'Hello World !'
echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin
docker push egorebasko/stuff-doer

echo 'Download Hyper.sh'
wget https://hyper-install.s3.amazonaws.com/hyper-linux-x86_64.tar.gz
tar xzf hyper-linux-x86_64.tar.gz