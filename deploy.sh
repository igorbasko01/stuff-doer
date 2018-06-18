#!/bin/bash
echo 'Hello World !'
echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin
docker push egorebasko/stuff-doer

echo 'Download Hyper.sh'
wget https://hyper-install.s3.amazonaws.com/hyper-linux-x86_64.tar.gz
tar xzf hyper-linux-x86_64.tar.gz
./hyper config --accesskey "$HYPER_ACCESS" --secretkey "$HYPER_SECRET"
./hyper login -u "$DOCKER_USERNAME" -p "$DOCKER_PASSWORD"
./hyper rm -f stuff-doer
./hyper pull egorebasko/stuff-doer
./hyper run -d -p 80:9080 -v stuff-igor:/root --name stuff-doer egorebasko/stuff-doer
./hyper fip attach "$FIP" stuff-doer