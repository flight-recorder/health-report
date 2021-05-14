# Description

Health Report is a Java program that demonstrates the JFR Event Streaming API.

It's both a Java agent and a .java file that can be launched as a single-file program. The agent runs alongside with an ordinary Java application and prints data produced by JFR to standard out. 

Health report requires JDK 16, or later, and it only works with OpenJDK/Oracle JDK.

For a demonstration see:

https://youtu.be/E9K5m1HXMSc?t=367

Example output:

<pre>
=================== HEALTH REPORT === 2019-05-16 23:57:50 ====================
| GC: G1Old/G1New         Phys. memory: 28669 MB    Alloc Rate: 8 MB/s       |
| OC Count    : 28        Initial Heap: 448 MB      Total Alloc: 190 MB      |
| OC Pause Avg: 40.1 ms   Used Heap   : 19 MB       Thread Count: 20.0       |
| OC Pause Max: 48.8 ms   Commit. Heap: 47 MB       Class Count : 3894.0     |
| YC Count    : 8         CPU Machine   :  20.12 %  Safepoints: 335          |
| YC Pause Avg: 5.7 ms    CPU JVM User  :  10.28 %  Max Safepoint: 46.4 ms   |
| YC Pause Max: 22.4 ms   CPU JVM System:   1.07 %  Max Comp. Time: 728.3 ms |
|--- Top Allocation Methods ------------------------------- -----------------|
| DataBufferInt.<init>(int)                                                11.27 % |
| Component.size()                                                    9.01 % |
| BufferedContext.validate(...)                                       6.21 % |
| Path2D$Double.<init>(...)                                                 5.87 % |
| SunGraphics2D.clone()                                               5.85 % |
|--- Hot Methods ------------------------------------------------------------|
| DRenderer._endRendering(int, int)                                  51.11 % |
| DRenderer.copyAARow(...)                                            6.67 % |
| Arrays.fill(...)                                                    4.44 % |
| StringConcatFactory.doStringConcat(...)                             2.22 % |
| MarlinTileGenerator.getAlphaNoRLE(...)                              2.22 % |
==============================================================================
</pre>

# Usage

To run Health Report as a Java agent, a jar-file must first be built:

    $ cd src
    $ javac HealthReport.java
    $ jar cmf META-INF/MANIFEST.MF health-report.jar .

The jar-file can be used with the -javaagent option together with the program that is going to be monitored:

    $ java -javaagent:health-report.jar MyApp

To run Health Report as a single-file program, specify the source file together with a target.
  
    $ java HealthReport.java <target>

The target can be a class, name of a .jar-file, a pid, a recording, a repository, or a network address. For example:

    $ java -XX:StartFlightRecording com.example.MyApp
    $ java HealthReport.java MyApp

If a stream can't be created, for example, if the target process hasn't started, the Health Report will retry until the target becomes available. This is a convenience so that Health Report doesn't need to be restarted every time the target process is restarted.

## Targets

### Repository

    $ java HealthReport.java /directory/recording.jfr
    $ java HealthReport.java /repository/
    $ java HealthReport.java /repository/2021_03_30_09_48_31_60185

The location of the repository for the target process can be specified using -XX:FlightRecordOptions

    $ java -XX:StartFlightRecording -XX:FlightRecordingOptions:repository=<directory> ...

### Program name and PID

    $ java HealthReport.java MyApplication
    $ java HealthReport.java com.example.MyApplication
    $ java HealthReport.java example.module/com.example.MyApplication
    $ java HealthReport.java example.jar

The target will match against the end of the program name, typically the class with the main method. If the target process isn't running with Flight Recorder, it can be started using the jcmd tool located in JAVA_HOME: 

    $ jcmd <name|pid> JFR.start 

If there are multiple program with the same name, It's possible to use the PID instead:

    $ java HealthReport.java 4711

A list of Java processes and their PIDs is displayed if HealthReport.java started without any arguments:

    $ java HeathReport.java

Processes that are running with JFR enabled are prefixed with [JFR]. If the process is not running with JFR, it is not possible to stream data from it.
 
### Network address

    $ java HealthReport.java example.com:7091
    $ java HealthReport.java 127.0.0.1:7092
    $ java HealthReport.java [0:0:0:0:0:0:0:1]:7093
    $ java HealthReport.java --timeout 30 localhost:7094

The network address is concatenation of the host and port separated by ':'. To be able to connect, the management agent must be started machine with the following properties:


    $ java -Dcom.sun.management.jmxremote.port=7091 -Dcom.sun.management.jmxremote.authenticate=false
           -Dcom.sun.management.jmxremote.ssl=false MyApp 

It's also possible to start the agent on already running process using the jcmd tool:

    $ jcmd MyApp ManagementAgent.start jmxremote.port=7091 jmxremote.authenticate=false jmxremote.ssl=false

More information on how to setup the management server can be found here https://docs.oracle.com/en/java/javase/16/management/monitoring-and-management-using-jmx-technology.html. Health Report doesn't support ssl and authentication, so it can't be used in an insecure environment. 


### Recording file

    $ java HealthReport.java recording.jfr
    $ java HealthReport.java /recordings/perf.jfr

By specifying --replay-speed, it's possible to determine how fast the recording should be played back. A speed of 1 will, replay the events in the same pace as they were recorded. A speed of 10, will replay them ten times as fast.

    $ java HealthReposrt.java --replay-speed 10 recording.jfr

By default, events are replayed at their maximum speed. 

### Self

    $ java HealthReport.java self

It's possible to specify 'self' to make Health Report connect to itself. It usually results in few events and is mostly useful for debugging purposes.

## Options

It's possible to specify options for both the Java agent and the single-file program. The Java agent uses ',' and '=' as delimiters instead of whitespace characters, for example:

     $ java -javaagent:health-report.jar=--scroll,--debug,--timeout=20 MyApp

### --scroll

The program will by default use ANSI characters to reposition the cursor after each update. To override that behavior, --scroll can be specified.

Example:

    $ java --scroll HealthReport.java MyApp

On Windows, scrolling is the default behavior.

### --timeout <integer>

If the target process becomes unresponsive, for example if it has crashed or the network connection dies, the program will try to reconnect. Timeout specify how long the program should wait before it should close the current stream and start a new. The default timeout 15 s.

Example: 

    $ java --timeout 5 HealthReport.java MyApp

### --debug

If the program is not able to start a stream against the target, you can specify --debug to see what the problem  was. 

Example:

    $ java --debug HealthReport.java MyApp

# Modification

The output can easily be changed by modifying the template string in HealthReport.java. To create a new data point, create a static fields and use the field name in the template. To see a list of event names and fields, you can use the jfr-tool located in JAVA_HOME:

   $ jfr metadata recording.jfr


# Known issues

- *Sometimes the "Top Allocation Methods" table is empty*

  In JDK 16, a new allocation event with less overhead was introduced (jdk.ObjectAllocationSample). This means the table will be empty for earlier release where the event is not available.

- *Sometimes there is no timestamp at the top*

  The displayed timestamp comes from jdk.Flush event which is not enabled by default. It Health Report is running as a Java agent or it connects to network host it is automatically turned. It is not possible to turn on when running against directory or a file. 