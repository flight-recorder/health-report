# Usage

    $ java HealthReport.java [options] <source>

## [options]

It's possible to pass options to Health Report, both when using it as a Java agent and a single-file program. The Java agent uses ',' and '=' as delimiters instead of whitespace characters, for example:

     $ java -javaagent:health-report.jar=--scroll,--debug,--timeout=20 com.example.MyApp

#### --scroll

Health Report uses ANSI characters to reposition the cursor after each update. To scroll the output instead, specify --scroll.

Example:

    $ java --scroll HealthReport.java MyApp

On Windows, scrolling is the default behavior.

#### --timeout <integer>

If the source becomes unresponsive, for example if the process has crashed or the network connection has died, Health Report will try to reestablish the connection. Timeout specify how long Health Report should wait before closing the current stream and starting a new. The default timeout is 15 s.

Example: 

    $ java --timeout 5 HealthReport.java MyApp

#### --replay-speed <integer>

The --replay-option can only be used when the source is a recording file. The option determines how fast events should be replayed. A speed of 1 will, replay the events in the same pace as they were recorded. A speed of 10, will replay them ten times as fast.

Example:

    $ java HealthReport.java --replay-speed 10 recording.jfr

By default, events are replayed at their maximum speed. 

#### --debug

If the program is not able to start Health Report, --debug can be specified to troubleshot the issue.

Example:

    $ java --debug HealthReport.java com.example.MyApp

## \<source> 

The source is where Health Report stream events from. It can be a Java process, a directory, a network address or a recording file. 

#### Source: Java process

Flight Recorder must be started before data can be streamed from a Java process, for example:

    $ java -XX:StartFlightRecording com.example.MyApp

The source will match from the end of the Java process name, typically the class with the main method. If the process isn't running with Flight Recorder, it can be started using the jcmd tool located in JAVA_HOME: 

    $ jcmd com.example.MyApp JFR.start 

If there are multiple processes with the same name, the PID can instead be used:

    $ jcmd 4711 JFR.start
    $ java HealthReport.java 4711

A list of Java processes and their PIDs are shown if Health Report is started without any arguments. Only processes listed with [JFR] is it possible to stream events from.

Examples:

    $java HealthReport.java com.example.MyApp
    $java HealthReport.java MyApp
    $java HealthReport.java 4711
    $java HealthReport.java my.jar

#### Source: Directory

Flight Recorder writes event data to a directory known as the repository. By default it is located in the temporary directory, but it can be set during startup using -XX:FlightRecordOptions:

    $ java -XX:StartFlightRecording -XX:FlightRecordingOptions:repository=/data com.example.MyApplication

By setting the source to the repository location, Health Report can stream events from other processes.

Examples:

    $ java HealthReport.java /data/
    $ java HealthReport.java /data/2021_03_30_09_48_31_60185

#### Source: Network address

Health Report can stream events over JMX, but it requires that the management agent is running on the host. The management agent can be started by specifying the following properties at startup:

    $ java -Dcom.sun.management.jmxremote.port=7091 -Dcom.sun.management.jmxremote.authenticate=false
           -Dcom.sun.management.jmxremote.ssl=false com.example.MyApp

The management agent can also be started using jcmd:

    $ jcmd com.example.MyApplication ManagementAgent.start jmxremote.port=7091 jmxremote.authenticate=false jmxremote.ssl=false

Examples:

    $ java HealthReport.java example.com:7091
    $ java HealthReport.java 127.0.0.1:7091
    $ java HealthReport.java [0:0:0:0:0:0:0:1]:7093

More information on how to setup the management agenet can be found [here](https://docs.oracle.com/en/java/javase/16/management/monitoring-and-management-using-jmx-technology.html). Health Report doesn't support ssl and authentication, so it can't be used in enviroments where security is a concern. 

#### Source: Recording file

Health Report can stream event from a recording file. A recording file can be created by specifying -XX:StartFlightRecording at startup, for example:

    $ java -XX:StartFlightRecording:filename=recording.jfr,duration=60s com.example.MyApp 

A recording file can also be created using jcmd:

    $ jcmd com.example.MyApp JFR.start filename=/directory/perf.jfr duration=60s

Examples:

    $ java HealthReport.java recording.jfr
    $ java HealthReport.java /directory/perf.jfr

#### Source: Self

It's possible to specify 'self' as a source to make Health Report stream against itself. It usually results in few events and is mostly useful for debugging purposes.

Example:

    $ java HealthReport.java self


