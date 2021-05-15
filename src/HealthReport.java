/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Queue;
import java.util.SortedMap;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import jdk.jfr.EventSettings;
import jdk.jfr.consumer.EventStream;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingStream;
import jdk.management.jfr.RemoteRecordingStream;

public final class HealthReport {

    private static final String TEMPLATE =
    """
    =================== HEALTH REPORT === $FLUSH_TIME         ====================
    | GC: $GC_NAME            Phys. memory: $PHYSIC_MEM Alloc Rate: $ALLOC_RATE  |
    | OC Count    : $OC_COUNT Initial Heap: $INIT_HEAP  Total Alloc: $TOT_ALLOC  |
    | OC Pause Avg: $OC_AVG   Used Heap   : $USED_HEAP  Thread Count: $THREADS   |
    | OC Pause Max: $OC_MAX   Commit. Heap: $COM_HEAP   Class Count : $CLASSES   |
    | YC Count    : $YC_COUNT CPU Machine   : $MACH_CPU Safepoints: $SAFEPOINTS  |
    | YC Pause Avg: $YC_AVG   CPU JVM User  : $USR_CPU  Max Safepoint: $MAX_SAFE |
    | YC Pause Max: $YC_MAX   CPU JVM System: $SYS_CPU  Max Comp. Time: $MAX_COM |
    |--- Top Allocation Methods ------------------------------- -----------------|
    | $ALLOCACTION_TOP_FRAME                                            $AL_PE   |
    | $ALLOCACTION_TOP_FRAME                                            $AL_PE   |
    | $ALLOCACTION_TOP_FRAME                                            $AL_PE   |
    | $ALLOCACTION_TOP_FRAME                                            $AL_PE   |
    | $ALLOCACTION_TOP_FRAME                                            $AL_PE   |
    |--- Hot Methods ------------------------------------------------------------|
    | $EXECUTION_TOP_FRAME                                              $EX_PE   |
    | $EXECUTION_TOP_FRAME                                              $EX_PE   |
    | $EXECUTION_TOP_FRAME                                              $EX_PE   |
    | $EXECUTION_TOP_FRAME                                              $EX_PE   |
    | $EXECUTION_TOP_FRAME                                              $EX_PE   |
    ==============================================================================
    """;
    Field FLUSH_TIME = new Field();

    Field GC_NAME = new Field();
    Field OC_COUNT = new Field(Option.COUNT);
    Field OC_AVG = new Field(Option.AVERAGE, Option.DURATION);
    Field OC_MAX = new Field(Option.MAX, Option.DURATION);
    Field YC_COUNT = new Field(Option.COUNT);
    Field YC_AVG = new Field(Option.AVERAGE, Option.DURATION);
    Field YC_MAX = new Field(Option.MAX, Option.DURATION);

    Field PHYSIC_MEM = new Field(Option.BYTES);
    Field INIT_HEAP = new Field(Option.BYTES);
    Field USED_HEAP = new Field(Option.BYTES);
    Field COM_HEAP = new Field(Option.BYTES);
    Field MACH_CPU = new Field(Option.PERCENTAGE);
    Field USR_CPU = new Field(Option.PERCENTAGE);
    Field SYS_CPU = new Field(Option.PERCENTAGE);

    Field ALLOC_RATE = new Field(Option.BYTES_PER_SECOND);
    Field TOT_ALLOC = new Field(Option.TOTAL, Option.BYTES);
    Field THREADS = new Field(Option.INTEGER);
    Field CLASSES = new Field(Option.INTEGER);
    Field SAFEPOINTS = new Field(Option.COUNT);
    Field MAX_SAFE = new Field(Option.MAX, Option.DURATION);
    Field MAX_COM = new Field(Option.MAX, Option.DURATION);

    Field ALLOCACTION_TOP_FRAME = new Field();
    Field AL_PE = new Field(Option.NORMALIZED, Option.TOTAL);

    Field EXECUTION_TOP_FRAME = new Field();
    Field EX_PE = new Field(Option.NORMALIZED, Option.COUNT);

    // Only recording streams can enable settings.
    private static EventSettings enable(EventStream es, String eventName) {
        if (es instanceof RemoteRecordingStream) {
            return ((RemoteRecordingStream)es).enable(eventName);
        } else {
            return ((RecordingStream)es).enable(eventName);
        }
    }

    // Starts a stream depending on source
    //
    // Source               EventStream
    // --------------------------------------------------
    // Main class        -> EventStream.openDirectory(AttachableProcess.getPath());
    // Directory         -> EventStream.openDirectory(Path);
    // File              -> EventStream.openFile(Path);
    // Network address   -> new RemoteRecordingStream(MBeanServerConnection);
    // "Self"            -> new RecordingStream();
    private void startStream(String source) throws Exception {
        try (EventStream es = createStream(source)) {
            if (es instanceof RemoteRecordingStream || es instanceof RecordingStream) {
                // Event configuration
                Duration duration = Duration.ofSeconds(1);
                enable(es, "jdk.CPULoad").withPeriod(duration);
                enable(es, "jdk.YoungGarbageCollection").withoutThreshold();
                enable(es, "jdk.OldGarbageCollection").withoutThreshold();
                enable(es, "jdk.GCHeapSummary").withPeriod(duration);
                enable(es, "jdk.PhysicalMemory").withPeriod(duration);
                enable(es, "jdk.GCConfiguration").withPeriod(duration);
                enable(es, "jdk.SafepointBegin");
                enable(es, "jdk.SafepointEnd");
                enable(es, "jdk.ObjectAllocationSample").with("throttle", "150/s");
                enable(es, "jdk.ExecutionSample").withPeriod(Duration.ofMillis(10)).withStackTrace();
                enable(es, "jdk.JavaThreadStatistics").withPeriod(duration);
                enable(es, "jdk.ClassLoadingStatistics").withPeriod(duration);
                enable(es, "jdk.Compilation").withoutThreshold();
                enable(es, "jdk.GCHeapConfiguration").withPeriod(duration);
                enable(es, "jdk.Flush").withoutThreshold();
            }

            // Dispatch handlers
            es.onEvent("jdk.CPULoad", this::onCPULoad);
            es.onEvent("jdk.YoungGarbageCollection", this::onYoungColletion);
            es.onEvent("jdk.OldGarbageCollection", this::onOldCollection);
            es.onEvent("jdk.GCHeapSummary", this::onGCHeapSummary);
            es.onEvent("jdk.PhysicalMemory", this::onPhysicalMemory);
            es.onEvent("jdk.GCConfiguration", this::onGCConfiguration);
            es.onEvent("jdk.SafepointBegin", this::onSafepointBegin);
            es.onEvent("jdk.SafepointEnd", this::onSafepointEnd);
            es.onEvent("jdk.ObjectAllocationSample", this::onAllocationSample);
            es.onEvent("jdk.ExecutionSample", this::onExecutionSample);
            es.onEvent("jdk.JavaThreadStatistics", this::onJavaThreadStatistics);
            es.onEvent("jdk.ClassLoadingStatistics", this::onClassLoadingStatistics);
            es.onEvent("jdk.Compilation", this::onCompilation);
            es.onEvent("jdk.GCHeapConfiguration", this::onGCHeapConfiguration);
            es.onEvent("jdk.Flush", this::onFlushpoint);

            var heartBeat = new AtomicReference<>(Instant.now());
            es.onFlush(() -> {
                heartBeat.set(Instant.now());
                printReport();
            });
            es.startAsync();
            while (Duration.between(heartBeat.get(), Instant.now()).toSeconds() < timeout) {
                Thread.sleep(100);
            }
            timedOut = true;
        }
    }

    private void onCPULoad(RecordedEvent event) {
        MACH_CPU.addSample(event.getDouble("machineTotal"));
        SYS_CPU.addSample(event.getDouble("jvmSystem"));
        USR_CPU.addSample(event.getDouble("jvmUser"));
    }

    private void onYoungColletion(RecordedEvent event) {
        long nanos = event.getDuration().toNanos();
        YC_COUNT.addSample(nanos);
        YC_MAX.addSample(nanos);
        YC_AVG.addSample(nanos);
    }

    private void onOldCollection(RecordedEvent event) {
        long nanos = event.getDuration().toNanos();
        OC_COUNT.addSample(nanos);
        OC_MAX.addSample(nanos);
        OC_AVG.addSample(nanos);
    }

    private void onGCHeapSummary(RecordedEvent event) {
        USED_HEAP.addSample(event.getLong("heapUsed"));
        COM_HEAP.addSample(event.getLong("heapSpace.committedSize"));
    }

    private void onPhysicalMemory(RecordedEvent event) {
        PHYSIC_MEM.addSample(event.getLong("totalSize"));
    }

    private void onCompilation(RecordedEvent event) {
        MAX_COM.addSample(event.getDuration().toNanos());
    }

    private void onGCConfiguration(RecordedEvent event) {
        String gc = event.getString("oldCollector");
        String yc = event.getString("youngCollector");
        if (yc != null) {
            gc += "/" + yc;
        }
        GC_NAME.addSample(gc);
    }

    private final Map<Long, Instant> safepointBegin = new HashMap<>();
    private void onSafepointBegin(RecordedEvent event) {
        safepointBegin.put(event.getValue("safepointId"), event.getEndTime());
    }

    private void onSafepointEnd(RecordedEvent event) {
        long id = event.getValue("safepointId");
        Instant begin = safepointBegin.get(id);
        if (begin != null) {
            long nanos = Duration.between(begin, event.getEndTime()).toNanos();
            safepointBegin.remove(id);
            SAFEPOINTS.addSample(nanos);
            MAX_SAFE.addSample(nanos);
        }
    }

    private double totalAllocated;
    private long firstAllocationTime = -1;
    private void onAllocationSample(RecordedEvent event) {
        long size = event.getLong("weight");
        String topFrame = topFrame(event.getStackTrace());
        if (topFrame != null) {
            ALLOCACTION_TOP_FRAME.addSample(topFrame, size);
            AL_PE.addSample(topFrame, size);
        }
        TOT_ALLOC.addSample(size);
        long timestamp = event.getEndTime().toEpochMilli();
        totalAllocated += size;
        if (firstAllocationTime > 0) {
            long elapsedTime = timestamp - firstAllocationTime;
            if (elapsedTime > 0) {
                double rate = 1000.0 * (totalAllocated / elapsedTime);
                ALLOC_RATE.addSample(rate);
            }
        } else {
            firstAllocationTime = timestamp;
        }
    }

    private void onExecutionSample(RecordedEvent event) {
        String topFrame = topFrame(event.getStackTrace());
        EXECUTION_TOP_FRAME.addSample(topFrame, 1);
        EX_PE.addSample(topFrame, 1);
    }

    private void onJavaThreadStatistics(RecordedEvent event) {
        THREADS.addSample(event.getDouble("activeCount"));
    }

    private void onClassLoadingStatistics(RecordedEvent event) {
        long diff = event.getLong("loadedClassCount") - event.getLong("unloadedClassCount");
        CLASSES.addSample(diff);
    }

    private void onGCHeapConfiguration(RecordedEvent event) {
        INIT_HEAP.addSample(event.getLong("initialSize"));
    }

    private final static DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private void onFlushpoint(RecordedEvent event) {
        LocalDateTime d = LocalDateTime.ofInstant(event.getEndTime(), ZoneOffset.systemDefault());
        FLUSH_TIME.addSample(FORMATTER.format(d));
    }

    // # # # AGENT # # #

    // Used when loading agent from command line
    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }

    // Used when loading agent during runtime.
    public static void agentmain(String args, Instrumentation inst) {
        String[] splitted = args == null ? new String[0] : args.split("=|,");
        String[] options = Arrays.copyOf(splitted, splitted.length + 1);
        options[options.length - 1] = "self";
        CompletableFuture.runAsync(() -> {
            main(options);
        });
    }

    private static int timeout = 15;
    private static boolean debug = false;
    private static boolean scroll = System.lineSeparator().equals("\r\n");
    private static int replaySpeed = 0;
    public static void main(String... args) {
        Queue<String> options = new ArrayDeque<>(List.of(args));
        while (options.size() > 1) {
            int optionCount = options.size();
            if (acceptOption(options, "--scroll")) {
                scroll = true;
            }
            if (acceptOption(options, "--debug")) {
                debug = true;
            }
            if (acceptOption(options, "--replay-speed")) {
                replaySpeed = parseInteger(options);
            }
            if (acceptOption(options, "--timeout")) {
                timeout = parseInteger(options);
            }
            if (options.size() == optionCount) { // No progress
                println("Unknown option: " + options.peek());
                options.clear();
            }
        }
        if (options.size() != 1) {
            printHelp();
            return;
        }
        String source = options.poll();
        while (true) {
            for (int i = 0; i < 78; i++) {
                try {
                    HealthReport h = new HealthReport();
                    h.startStream(source);
                    if (h.isFile) {
                        return; // no retry with files.
                    }
                    println("Time out! Retrying.");
                    break;
                } catch (Exception e) {
                    debug("\n" + e.getMessage());
                }
                takeNap(1000);
                System.out.print(".");
            }
            println("");
        }
    }

    private static int parseInteger(Queue<String> options) {
        String value = options.poll();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException nfe) {
            println("Not a valid integer value: " + value);
            options.clear();
        }
        return 0;
    }

    private static boolean acceptOption(Queue<String> options, String option) {
        if (options.peek().equals(option)) {
            options.poll();
            return true;
        }
        return false;
    }

    private static void printHelp() {
        println("Usage:");
        println("java HealthReport.java [options] <main-class|pid|host|file|directory>");
        println("");
        println("Options:");
        println(" --scroll                Don't use ANSI cursor movement. True for Windows.");
        println(" --debug                 Print debug information");
        println(" --timeout <int>         Seconds to wait before reestablishing stream");
        println(" --replay-speed <int>    Speedup factor. Only works with files.");
        println("");
        println("Examples:");
        println("java HealthReport.java MyApplication");
        println("java HealthReport.java com.example.MyApplication");
        println("java HealthReport.java example.module/com.example.MyApplication");
        println("java HealthReport.java application.jar");
        println("java HealthReport.java /programs/application.jar");
        println("java HealthReport.java 4711");
        println("java HealthReport.java /repository/");
        println("java HealthReport.java /repository/2021_03_30_09_48_31_60185");
        println("java HealthReport.java example.com:7091");
        println("java HealthReport.java 127.0.0.1:7092");
        println("java HealthReport.java [0:0:0:0:0:0:0:1]:7093");
        println("java HealthReport.java service:jmx:rmi:///jndi/rmi://com.example:7091/jmxrmi");
        println("java HealthReport.java recording.jfr");
        println("java HealthReport.java /directory/perf.jfr");
        println("java HealthReport.java --replay-speed 10 recording.jfr");
        println("java HealthReport.java self");
        println("");
        List<AttachableProcess> aps = listProcesses();
        if (!aps.isEmpty()) {
            println("Java Processes:");
            aps.forEach(p -> println(p.toString()));
        } else {
            println("Found no running Java processes");
        }
        println("");
    }

    private static void println(String line) {
        System.out.println(line);
    }

    private static void debug(String line) {
        if (debug) {
            System.err.println(line);
        }
    }

    // # # # INSTANTIATE EVENT STREAM# # #

    private EventStream createStream(String source) throws Exception {
        if (source.equals("self")) {
            return new RecordingStream();
        }
        Path path = makePath(source);
        if (path != null) {
            return createFromPath(path);
        }
        JMXServiceURL url = makeJMXServiceURL(source);
        if (url != null) {
            return createRemoteStream(url);
        }
        Path repository = findRepository(source);
        if (repository != null) {
            return EventStream.openRepository(repository);
        }
        throw new Exception("Could not open : " + source);
    }

    private boolean isFile;
    private EventStream createFromPath(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            // With JDK 17 it is not necessary to locate subdirectory
            SortedMap<Instant, Path> sorted = new TreeMap<>();
            Files.list(path).forEach(sub -> {
                try {
                    if (Files.isDirectory(sub)) {
                        FileTime ft = Files.getLastModifiedTime(sub);
                        Instant t = ft.toInstant();
                        sorted.put(t, sub);
                    }
                } catch (IOException e) {
                }
            });
            if (sorted.isEmpty()) {
                return EventStream.openRepository(path);
            } else {
                Instant latest = sorted.lastKey();
                Path dir = sorted.get(latest);
                return EventStream.openRepository(dir);
            }
        } else {
            EventStream es = EventStream.openFile(path);
            isFile = true;
            es.onClose( () -> {
                timeout = 0; // aborts stream
            });
            if (replaySpeed != 0) {
                es.onFlush(() -> takeNap(1000 / replaySpeed));
            }
            return es;
        }
    }

    private EventStream createRemoteStream(JMXServiceURL url) throws IOException {
        JMXConnector c = JMXConnectorFactory.newJMXConnector(url, null);
        c.connect();
        EventStream es = new RemoteRecordingStream(c.getMBeanServerConnection());
        es.onClose(() -> {
            CompletableFuture.runAsync(() -> {
                try {
                    c.close();
                } catch (IOException e) {
                }
            });
        });
        return es;
    }

    private static JMXServiceURL makeJMXServiceURL(String source) {
        try {
            return new JMXServiceURL(source);
        } catch (MalformedURLException e) {
        }
        try {
            String[] s = source.split(":");
            if (s.length == 2) {
                String host = s[0];
                String port = s[1];
                return new JMXServiceURL("rmi", "", 0, "/jndi/rmi://" + host + ":" + port + "/jmxrmi");
            }
        } catch (MalformedURLException e) {
        }
        return null;
    }

    private static Path makePath(String source) {
        try {
            Path p = Path.of(source);
            if (Files.exists(p)) {
                return p;
            }
        } catch (InvalidPathException ipe) {
        }
        return null;
    }

    record AttachableProcess(VirtualMachineDescriptor desc, String path) {
        @Override
        public String toString() {
            String jfr = path != null ? "[JFR]" : "     ";
            return String.format("%-5s %s %s", desc.id(), jfr, desc.displayName());
        }
    }

    private static Path findRepository(String source) {
        for (AttachableProcess p : listProcesses()) {
            if (source.equals(p.desc().id()) || p.desc().displayName().endsWith(source)) {
                return Path.of(p.path());
            }
        }
        return null;
    }

    private static List<AttachableProcess> listProcesses() {
        List<AttachableProcess> list = new ArrayList<>();
        try {
            for (VirtualMachineDescriptor vm : VirtualMachine.list()) {
                try {
                    VirtualMachine jvm = VirtualMachine.attach(vm);
                    Properties p = jvm.getSystemProperties();
                    String path = p.getProperty("jdk.jfr.repository");
                    jvm.detach();
                    list.add(new AttachableProcess(vm, path));
                } catch (Exception e) {
                    debug(e.getMessage());
                }
            }
        } catch (Exception e) {
            debug(e.getMessage());
        }
        return list;
    }

    // # # # TEMPLATE AND SAMPLING # # #

    private enum Option {
        BYTES,
        PERCENTAGE,
        DURATION,
        BYTES_PER_SECOND,
        NORMALIZED, COUNT,
        AVERAGE,
        TOTAL,
        MAX,
        INTEGER
    }

    private static final class Record {
        private final Object key;
        private int count = 1;
        private double total;
        private double max;
        private Object value = null;

        public Record(Object key, String sample) {
            this.key = key;
            this.value = sample;
        }

        public Record(Object object, double sample) {
            this.key = object;
            this.value = sample;
            this.max = sample;
            this.total = sample;
        }

        public long getCount() {
            return count;
        }

        public double getAverage() {
            return total / count;
        }

        public double getMax() {
            return max;
        }

        public double getTotal() {
            return total;
        }

        @Override
        public int hashCode() {
            return key == null ? 0 : key.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Record that) {
                return Objects.equals(that.key, this.key);
            }
            return false;
        }
    }

    private static final class Field implements Iterable<Record> {
        private final HashMap<Object, Record> histogram = new HashMap<>();
        private final Option[] options;
        private double norm;

        public Field(Option... options) {
            this.options = options;
        }

        public void addSample(double sample) {
            addSample(this, sample);
        }

        public void addSample(String sample) {
            histogram.merge(this, new Record(this, sample), (a, b) -> {
                a.count++;
                a.value = sample;
                return a;
            });
        }

        public void addSample(Object key, double sample) {
            histogram.merge(key, new Record(key, sample), (a, b) -> {
                a.count++;
                a.total += sample;
                a.value = sample;
                a.max = Math.max(a.max, sample);
                return a;
            });
        }

        public boolean hasOption(Option option) {
            for (Option o : options) {
                if (o == option) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Iterator<Record> iterator() {
            List<Record> records = new ArrayList<>(histogram.values());
            Collections.sort(records, (a, b) -> Long.compare(b.getCount(), a.getCount()));
            if (hasOption(Option.TOTAL)) {
                Collections.sort(records, (a, b) -> Double.compare(b.getTotal(), a.getTotal()));
            }
            if (hasOption(Option.NORMALIZED)) {
                norm = 0.0;
                for (Record r : records) {
                    if (hasOption(Option.TOTAL)) {
                        norm += r.getTotal();
                    }
                    if (hasOption(Option.COUNT)) {
                        norm += r.getCount();
                    }
                }
            }
            return records.iterator();
        }

        public double getNorm() {
            return norm;
        }
    }

    private boolean printed;
    private volatile boolean timedOut;
    private void printReport() {
        if (timedOut) {
            return;
        }
        try {
            if (!printed) {
                println(""); // Start report on new line
            }
            StringBuilder template = new StringBuilder(TEMPLATE);
            for (var f : HealthReport.class.getDeclaredFields()) {
                String variable = "$" + f.getName();
                if (f.getType() == Field.class) {
                    writeParam(template, variable, (Field) f.get(this));
                }
            }
            if (!scroll && printed) {
                long lines = TEMPLATE.lines().count() + 2;
                println("\u001b[" + lines + "A");
            }
            println(template.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
        printed = true;
    }

    private static void writeParam(StringBuilder template, String variable, Field param) {
        Iterator<Record> it = param.iterator();
        int lastIndex = 0;
        while (true) {
            int index = template.indexOf(variable, lastIndex);
            if (index == -1) {
                return;
            }
            lastIndex = index + 1;
            Record record = it.hasNext() ? it.next() : null;
            Object value = null;
            if (record != null) {
                value = (record.key == param) ? record.value : record.key;
            }
            if (value != null) {
                if (param.hasOption(Option.MAX)) {
                    value = record.getMax();
                }
                if (param.hasOption(Option.COUNT)) {
                    value = record.getCount();
                }
                if (param.hasOption(Option.AVERAGE)) {
                    value = record.getAverage();
                }
                if (param.hasOption(Option.TOTAL)) {
                    value = record.getTotal();
                }
            }
            if (param.hasOption(Option.COUNT)) {
                value = value == null ? 0 : value;
            }
            if (param.hasOption(Option.INTEGER)) {
                if (value instanceof Number n) {
                    value = n.longValue();
                }
            }
            if (param.hasOption(Option.BYTES)) {
                value = formatBytes((Number) value);
            }
            if (param.hasOption(Option.DURATION)) {
                value = formatDuration((Number) value);
            }
            if (param.hasOption(Option.BYTES_PER_SECOND)) {
                if (value != null) {
                    value = formatBytes((Number) value) + "/s";
                }
            }
            if (param.hasOption(Option.NORMALIZED)) {
                if (value != null) {
                    double d = ((Number) value).doubleValue() / param.getNorm();
                    value = formatPercentage(d);
                }
            }
            if (param.hasOption(Option.PERCENTAGE)) {
                value = formatPercentage((Number) value);
            }
            String text;
            if (value == null) {
                text = record == null ? "" : "N/A";
            } else {
                text = String.valueOf(value);
            }
            int length = Math.max(text.length(), variable.length());
            for (int i = 0; i < length; i++) {
                char c = i < text.length() ? text.charAt(i) : ' ';
                template.setCharAt(index + i, c);
            }
        }
    }

    private static void takeNap(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignore) {
        }
    }

    // # # # FORMATTING # # #

    enum TimespanUnit {
        NANOSECONDS("ns", 1000), MICROSECONDS("us", 1000), MILLISECONDS("ms", 1000),
        SECONDS("s", 60), MINUTES("m", 60), HOURS("h", 24), DAYS("d", 7);

        final String text;
        final long amount;
        TimespanUnit(String unit, long amount) {
            this.text = unit;
            this.amount = amount;
        }
    }

    private static String formatDuration(Number value) {
        if (value == null) {
            return "N/A";
        }
        double t = value.doubleValue();
        TimespanUnit result = TimespanUnit.NANOSECONDS;
        for (TimespanUnit unit : TimespanUnit.values()) {
            result = unit;
            if (t < 1000) {
                break;
            }
            t = t / unit.amount;
        }
        return String.format("%.1f %s", t, result.text);
    }

    private static String formatPercentage(Number value) {
        if (value == null) {
            return "N/A";
        }
        return String.format("%6.2f %%", value.doubleValue() * 100);
    }

    private static String formatBytes(Number value) {
        if (value == null) {
            return "N/A";
        }
        long bytes = value.longValue();
        if (bytes >= 1024 * 1024) {
            return bytes / (1024 * 1024) + " MB";
        }
        if (bytes >= 1024) {
            return bytes / 1024 + " kB";
        }
        return bytes + " bytes";
    }

    private static String topFrame(RecordedStackTrace stackTrace) {
        if (stackTrace == null) {
            return null;
        }
        List<RecordedFrame> frames = stackTrace.getFrames();
        if (!frames.isEmpty()) {
            RecordedFrame topFrame = frames.get(0);
            if (topFrame.isJavaFrame()) {
                return formatMethod(topFrame.getMethod());
            }
        }
        return null;
    }

    private static String formatMethod(RecordedMethod m) {
        StringBuilder sb = new StringBuilder();
        String typeName = m.getType().getName();
        typeName = typeName.substring(typeName.lastIndexOf('.') + 1);
        sb.append(typeName).append(".").append(m.getName());
        sb.append("(");
        StringJoiner sj = new StringJoiner(", ");
        String md = m.getDescriptor().replace("/", ".");
        String parameter = md.substring(1, md.lastIndexOf(")"));
        for (String qualifiedName : decodeDescriptors(parameter)) {
            sj.add(qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1));
        }
        sb.append(sj.length() > 10 ? "..." : sj);
        sb.append(")");
        return sb.toString();
    }

    private static List<String> decodeDescriptors(String descriptor) {
        List<String> descriptors = new ArrayList<>();
        for (int index = 0; index < descriptor.length(); index++) {
            String arrayBrackets = "";
            while (descriptor.charAt(index) == '[') {
                arrayBrackets += "[]";
                index++;
            }
            String type = switch (descriptor.charAt(index)) {
                case 'L' -> {
                    int endIndex = descriptor.indexOf(';', index);
                    String s = descriptor.substring(index + 1, endIndex);
                    index = endIndex;
                    yield s;
                }
                case 'I' -> "int";
                case 'J' -> "long";
                case 'Z' -> "boolean";
                case 'D' -> "double";
                case 'F' -> "float";
                case 'S' -> "short";
                case 'C' -> "char";
                case 'B' -> "byte";
                default -> "<unknown-descriptor-type>";
            };
            descriptors.add(type + arrayBrackets);
        }
        return descriptors;
    }
}
