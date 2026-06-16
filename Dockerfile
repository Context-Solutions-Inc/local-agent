# Mobile-agent build/test container.
#
# Bakes the toolchain (JDK 17, Android SDK 36, Node 22, Claude Code CLI, gh, and
# Python 3.11 + the classifier-training stack) into the image; the project source
# is bind-mounted from the host at runtime so edits are live and build outputs
# land back on the host. No Gemma model is baked (download path is unchanged).
#
# Two pins worth knowing:
#   - JDK 17 + Android SDK platform 36 / build-tools 36.0.0 match CLAUDE.md.
#   - cmdline-tools 13.0 (revision 13114758) is the SDK installer; bump both
#     CMDLINE_TOOLS_VERSION and CMDLINE_TOOLS_SHA256 together if you update.

FROM eclipse-temurin:17-jdk-jammy

ARG USER_NAME=dev
ARG USER_UID=1000
ARG USER_GID=1000
ARG CMDLINE_TOOLS_VERSION=13114758
ARG CMDLINE_TOOLS_SHA256=7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee
ARG ANDROID_PLATFORM=android-36
ARG ANDROID_BUILD_TOOLS=36.0.0
ARG NODE_MAJOR=22

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=UTC \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/home/${USER_NAME}/.gradle \
    JAVA_TOOL_OPTIONS=""

# System deps: build essentials + tools the SDK and Claude Code expect.
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        gnupg \
        less \
        locales \
        nano \
        openssh-client \
        ripgrep \
        sudo \
        tzdata \
        unzip \
        wget \
        zip \
        zlib1g \
    && locale-gen en_US.UTF-8 \
    && rm -rf /var/lib/apt/lists/*

# Node.js (for Claude Code CLI) via NodeSource.
RUN curl -fsSL https://deb.nodesource.com/setup_${NODE_MAJOR}.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/* \
    && npm install -g @anthropic-ai/claude-code

# GitHub CLI (gh) via the official apt repo. Runs before the USER switch so no
# sudo is needed. Auth is runtime: `gh auth login` (persisted in the RW-bound
# ~/.config/gh, see docker-compose.yml), or set GH_TOKEN to override.
RUN mkdir -p -m 755 /etc/apt/keyrings \
    && wget -nv -O- https://cli.github.com/packages/githubcli-archive-keyring.gpg \
        | tee /etc/apt/keyrings/githubcli-archive-keyring.gpg > /dev/null \
    && chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
        > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends gh \
    && rm -rf /var/lib/apt/lists/*

# Android SDK: cmdline-tools, platform, build-tools, platform-tools.
RUN set -eux; \
    mkdir -p ${ANDROID_HOME}/cmdline-tools; \
    cd /tmp; \
    wget -q "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
         -O cmdline-tools.zip; \
    echo "${CMDLINE_TOOLS_SHA256}  cmdline-tools.zip" | sha256sum -c -; \
    unzip -q cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools; \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest; \
    rm cmdline-tools.zip; \
    yes | ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null; \
    ${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager \
        "platform-tools" \
        "platforms;${ANDROID_PLATFORM}" \
        "build-tools;${ANDROID_BUILD_TOOLS}" > /dev/null

ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator:${PATH}"

# Python 3.11 + the classifier-training stack (dataset gen/review/dedup/stats and
# the full training/export pipeline — the ct-* CLIs). The project requires
# Python >=3.11; jammy ships 3.10, so pull 3.11 from the deadsnakes PPA.
RUN apt-get update \
    && apt-get install -y --no-install-recommends software-properties-common \
    && add-apt-repository -y ppa:deadsnakes/ppa \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        pkg-config \
        python3.11 \
        python3.11-dev \
        python3.11-venv \
    && rm -rf /var/lib/apt/lists/*

# Deps are baked into a venv at /opt/venv; the package itself is installed EDITABLE
# against the REAL project path. At runtime the bind-mount overlays that same path
# with the live host source, so the ct-* CLIs run against your working tree with
# every dependency already resolved (zero install latency per `docker compose run`).
# torch resolves to the CUDA build (transitively pinned to ==2.9.x by ai-edge-torch,
# so a +cpu pin here would couple to that and break on the next bump). It runs fine
# on CPU — the default, since compose requests no GPU — and the same wheel is
# GPU-ready if you launch the container with `--gpus all` for training. (Heads-up:
# litert-torch logs "Skipping import of cpp extensions" because it wants torch>=2.11
# while ai-edge-torch holds it at 2.9.1; the pure-Python path the ct-* CLIs use is
# unaffected — that tension lives in the project's pyproject, not this image.)
ENV VIRTUAL_ENV=/opt/venv \
    PATH="/opt/venv/bin:${PATH}"
COPY classifier-training/pyproject.toml classifier-training/README.md \
     /home/lawrenceley/src/local-agent/classifier-training/
COPY classifier-training/src \
     /home/lawrenceley/src/local-agent/classifier-training/src
RUN python3.11 -m venv "${VIRTUAL_ENV}" \
    && pip install --no-cache-dir --upgrade pip setuptools wheel \
    && pip install --no-cache-dir -e \
        "/home/lawrenceley/src/local-agent/classifier-training[dev,training,claude,dedup]"

# Non-root user matching the host UID/GID so bind-mounted files keep host ownership.
# Pre-create cache dirs that docker-compose mounts named volumes onto — Docker
# copies their ownership into the new volume; without this the mountpoints are
# root-owned and Gradle can't write to ~/.gradle/wrapper.
RUN groupadd --gid ${USER_GID} ${USER_NAME} \
    && useradd --uid ${USER_UID} --gid ${USER_GID} --create-home --shell /bin/bash ${USER_NAME} \
    && echo "${USER_NAME} ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/${USER_NAME} \
    && chmod 0440 /etc/sudoers.d/${USER_NAME} \
    && mkdir -p /home/${USER_NAME}/.gradle /home/${USER_NAME}/.android /home/${USER_NAME}/.npm \
    && chown -R ${USER_UID}:${USER_GID} ${ANDROID_HOME} /opt/venv /home/${USER_NAME}

USER ${USER_NAME}
WORKDIR /home/${USER_NAME}

COPY --chown=${USER_UID}:${USER_GID} docker/entrypoint.sh /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["bash"]
