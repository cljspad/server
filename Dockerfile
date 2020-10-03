FROM amazoncorretto:11 as build-env

ADD https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein /bin/lein
RUN chmod +x /bin/lein
RUN lein version

WORKDIR /usr/src/cljspad
COPY project.clj /usr/src/cljspad/
RUN lein deps

COPY . /usr/src/cljspad
RUN lein uberjar

# Application build

FROM amazoncorretto:11

RUN mkdir -p /opt/cljspad/lib
COPY --from=build-env /usr/src/cljspad/target/*-standalone.jar /opt/cljspad/lib/cljspad.jar
CMD java -XX:InitialRAMPercentage=85 -XX:MaxRAMPercentage=85 -jar /opt/cljspad/lib/cljspad.jar
