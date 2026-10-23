# Build environment: JDK 21 + Android SDK + Gradle. Host toolchain stays untouched.
FROM eclipse-temurin:21-jdk

RUN apt-get update && apt-get install -y --no-install-recommends unzip wget binutils fakeroot \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
ARG CMDLINE_TOOLS=commandlinetools-linux-15859902_latest.zip
RUN mkdir -p $ANDROID_HOME/cmdline-tools \
    && wget -q https://dl.google.com/android/repository/$CMDLINE_TOOLS -O /tmp/ct.zip \
    && unzip -q /tmp/ct.zip -d /tmp/ct \
    && mv /tmp/ct/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
    && rm -rf /tmp/ct /tmp/ct.zip
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools" > /dev/null \
    && chmod -R a+rwX $ANDROID_HOME

ARG GRADLE_VERSION=9.8.2
RUN wget -q https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -O /tmp/g.zip \
    && unzip -q /tmp/g.zip -d /opt \
    && ln -s /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle \
    && rm /tmp/g.zip
