#!/bin/bash
set -e
echo "######################## BUILD GATEWAY IMAGE START... ########################"
docker build -t mirrors.tencent.com/bkci/gateway:0.0.1 . -f ./dockerfile/gateway.Dockerfile
echo "######################## BUILD GATEWAY IMAGE FINISH ! ########################"
echo ''
