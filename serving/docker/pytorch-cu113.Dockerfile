# -*- mode: dockerfile -*-
# Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file
# except in compliance with the License. A copy of the License is located at
#
# http://aws.amazon.com/apache2.0/
#
# or in the "LICENSE.txt" file accompanying this file. This file is distributed on an "AS IS"
# BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, express or implied. See the License for
# the specific language governing permissions and limitations under the License.
ARG version=11.3.1-cudnn8-devel-ubuntu20.04

FROM nvidia/cuda:$version as base

ARG djl_version=0.19.0~SNAPSHOT
ARG torch_version=1.12.1

RUN mkdir -p /opt/djl/conf
COPY config.properties /opt/djl/conf/
COPY dockerd-entrypoint.sh /usr/local/bin/dockerd-entrypoint.sh

WORKDIR /opt/djl
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
ENV OMP_NUM_THREADS=1
ENV MODEL_SERVER_HOME=/opt/djl
ENV PYTORCH_LIBRARY_PATH=/usr/local/lib/python3.8/dist-packages/torch/lib
ENV PYTORCH_PRECXX11=true
ENV PYTORCH_VERSION=${torch_version}
ENV PYTORCH_FLAVOR=cu116-precxx11
ENV JAVA_OPTS="-Xmx1g -Xms1g -XX:-UseContainerSupport -XX:+ExitOnOutOfMemoryError -Dai.djl.default_engine=PyTorch"

COPY scripts scripts/
RUN chmod +x /usr/local/bin/dockerd-entrypoint.sh && \
    scripts/install_djl_serving.sh $djl_version && \
    scripts/install_djl_serving.sh $djl_version ${torch_version} && \
    scripts/install_python.sh && \
    pip3 install numpy && pip3 install torch==${torch_version} --extra-index-url https://download.pytorch.org/whl/cu113 && \
    scripts/patch_oss_dlc.sh python && \
    rm -rf scripts && pip3 cache purge && \
    apt-get clean -y && rm -rf /var/lib/apt/lists/*

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/dockerd-entrypoint.sh"]
CMD ["serve"]

LABEL maintainer="djl-dev@amazon.com"

FROM base as parallelformers

ARG transformers_version=4.22.1
ARG parallelformers_version=1.2.6

COPY scripts scripts/
RUN pip3 install transformers==${transformers_version} parallelformers==${parallelformers_version} accelerate bitsandbytes && \
    scripts/patch_oss_dlc.sh python && \
    pip cache purge && rm -rf scripts

ENV MODEL_LOADING_TIMEOUT=2400
ENV SERVING_LOAD_ON_DEVICES=0
