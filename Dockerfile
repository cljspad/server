FROM amazoncorretto:11 as build-env

ADD https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein /bin/lein
RUN chmod +x /bin/lein
RUN lein version

WORKDIR /usr/src/cljsfiddle
COPY project.clj /usr/src/cljsfiddle/
RUN lein deps

COPY . /usr/src/cljsfiddle
RUN lein uberjar

# Application build

FROM amazoncorretto:11

RUN mkdir -p /opt/cljsfiddle/lib
COPY --from=build-env /usr/src/cljsfiddle/target/*-standalone.jar /opt/cljsfiddle/lib/cljsfiddle.jar
CMD java -XX:InitialRAMPercentage=85 -XX:MaxRAMPercentage=85 -jar /opt/cljsfiddle/lib/cljsfiddle.jar
