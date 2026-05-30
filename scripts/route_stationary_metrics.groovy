#!/usr/bin/env groovy

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

System.setProperty('file.encoding', 'UTF-8')
System.out = new PrintStream(System.out, true, 'UTF-8')
System.err = new PrintStream(System.err, true, 'UTF-8')

if (args.length < 1) {
    System.err.println 'Użycie: groovy gps_stationary_metrics.groovy <plik.csv> [nazwa_trasy]'
    System.exit(1)
}

def inputFile = new File(args[0])
if (!inputFile.exists()) {
    System.err.println "Nie znaleziono pliku: ${args[0]}"
    System.exit(1)
}

def routeName = args.length > 1 ? args[1] : inputFile.name

def formatter = DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm:ss')
def rows = []

def parseCsvLine
parseCsvLine = { String line ->
    def result = []
    def current = new StringBuilder()
    boolean inQuotes = false
    for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i)
        if (c == '"') {
            inQuotes = !inQuotes
        } else if (c == ',' && !inQuotes) {
            result << current.toString()
            current.setLength(0)
        } else {
            current.append(c)
        }
    }
    result << current.toString()
    result.collect { it.trim() }
}

inputFile.withReader('UTF-8') { reader ->
    def headerLine = reader.readLine()
    if (!headerLine) {
        System.err.println 'Brak nagłówka CSV'
        System.exit(1)
    }
    def header = parseCsvLine(headerLine).collect { it.replace('"', '') }

    def idx = [
            latitude : header.indexOf('latitude'),
            longitude: header.indexOf('longitude'),
            timestamp: header.indexOf('timestamp'),
            cps      : header.indexOf('cps'),
            cpm      : header.indexOf('cpm'),
            usv      : header.indexOf('usv_per_hr'),
            mode     : header.indexOf('mode')
    ]

    ['latitude', 'longitude', 'timestamp'].each { key ->
        if (idx[key] < 0) {
            System.err.println "Brak wymaganej kolumny: ${key}"
            System.exit(1)
        }
    }

    reader.eachLine { line ->
        if (!line?.trim()) return
        def cols = parseCsvLine(line)
        def lat = cols[idx.latitude].replace('"', '')
        def lon = cols[idx.longitude].replace('"', '')
        def ts = cols[idx.timestamp].replace('"', '')
        rows << [
                latitude : lat as BigDecimal,
                longitude: lon as BigDecimal,
                timestamp: LocalDateTime.parse(ts, formatter),
                cps      : idx.cps >= 0 && cols[idx.cps] ? cols[idx.cps].replace('"', '') as Integer : null,
                cpm      : idx.cpm >= 0 && cols[idx.cpm] ? cols[idx.cpm].replace('"', '') as Integer : null,
                usv      : idx.usv >= 0 && cols[idx.usv] ? cols[idx.usv].replace('"', '') as BigDecimal : null,
                mode     : idx.mode >= 0 ? cols[idx.mode].replace('"', '') : null
        ]
    }
}

if (rows.isEmpty()) {
    System.err.println 'Brak danych w pliku CSV'
    System.exit(1)
}

rows = rows.sort { a, b -> a.timestamp <=> b.timestamp }

double earthRadius = 6371000.0d

def haversine = { double lat1, double lon1, double lat2, double lon2 ->
    double dLat = Math.toRadians(lat2 - lat1)
    double dLon = Math.toRadians(lon2 - lon1)
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    earthRadius * c
}

def mean = { List<Double> values -> values ? values.sum() / values.size() : Double.NaN }
def median = { List<Double> values ->
    if (!values) return Double.NaN
    def s = values.sort()
    int n = s.size()
    n % 2 == 1 ? s[n.intdiv(2)] : (s[n.intdiv(2) - 1] + s[n.intdiv(2)]) / 2.0d
}
def stddev = { List<Double> values ->
    if (!values || values.size() < 2) return 0.0d
    double m = mean(values)
    Math.sqrt(values.collect { (it - m) * (it - m) }.sum() / (values.size() - 1))
}
def percentile = { List<Double> values, double p ->
    if (!values) return Double.NaN
    def s = values.sort()
    int idx = Math.ceil(s.size() * p) as int
    idx = Math.max(1, Math.min(idx, s.size())) - 1
    s[idx]
}

def movingAverage = { List<Double> values, int window ->
    if (!values || values.size() < window) return []
    (0..<(values.size() - window + 1)).collect { start ->
        values.subList(start, start + window).sum() / window
    }
}

def plNum = { Number value, int scale = 2 ->
    if (value == null || value instanceof Double && value.isNaN()) return ''
    String.format(Locale.US, "% .${scale}f", value).trim().replace('.', ',')
}

def plDate = { LocalDateTime dt -> dt.format(DateTimeFormatter.ofPattern('dd.MM.yyyy HH:mm:ss')) }
def plDuration = { Duration d ->
    long h = d.toHours()
    long m = d.toMinutesPart()
    long s = d.toSecondsPart()
    h > 0 ? String.format('%02d:%02d:%02d', h, m, s) : String.format('%02d:%02d', m, s)
}

List<Double> latValues = rows.collect { it.latitude as double }
List<Double> lonValues = rows.collect { it.longitude as double }
List<Double> usvValues = rows.findAll { it.usv != null }.collect { it.usv as double }
List<Double> cpsValues = rows.findAll { it.cps != null }.collect { it.cps as double }
List<Double> cpmValues = rows.findAll { it.cpm != null }.collect { it.cpm as double }

double meanLat = mean(latValues)
double meanLon = mean(lonValues)

def distancesToCenter = rows.collect {
    haversine(it.latitude as double, it.longitude as double, meanLat, meanLon)
}

def successiveDistances = []
def intervals = []
for (int i = 1; i < rows.size(); i++) {
    successiveDistances << haversine(
            rows[i - 1].latitude as double,
            rows[i - 1].longitude as double,
            rows[i].latitude as double,
            rows[i].longitude as double
    )
    intervals << Duration.between(rows[i - 1].timestamp, rows[i].timestamp).toMillis() / 1000.0d
}

def duration = Duration.between(rows.first().timestamp, rows.last().timestamp)

def usvMa5 = movingAverage(usvValues, 5)
def usvMa10 = movingAverage(usvValues, 10)
double meanUsv = mean(usvValues)
double stdUsv = stddev(usvValues)
double cvUsv = meanUsv != 0d ? (stdUsv / meanUsv) * 100.0d : Double.NaN

println "# Metryki stacjonarne dla trasy `${routeName}`"
println ''
println '## Podstawowe informacje'
println ''
println '| Metryka | Wartość |'
println '|---|---|'
println "| Liczba próbek | ${rows.size()} |"
println "| Czas początku | ${plDate(rows.first().timestamp)} |"
println "| Czas końca | ${plDate(rows.last().timestamp)} |"
println "| Czas trwania | ${plDuration(duration)} |"
println "| Tryb | ${rows.find { it.mode }?.mode ?: ''} |"
println "| Środek klastra | ${plNum(meanLat, 6)}, ${plNum(meanLon, 6)} |"
println "| Zakres latitude | ${plNum(latValues.min(), 6)} - ${plNum(latValues.max(), 6)} |"
println "| Zakres longitude | ${plNum(lonValues.min(), 6)} - ${plNum(lonValues.max(), 6)} |"
println "| Średnia odległość od środka [m] | ${plNum(mean(distancesToCenter))} |"
println "| Maksymalna odległość od środka [m] | ${plNum(distancesToCenter.max())} |"
println "| Percentyl 95 odległości od środka [m] | ${plNum(percentile(distancesToCenter, 0.95d))} |"
println "| Średnia odległość między kolejnymi punktami [m] | ${plNum(mean(successiveDistances))} |"
println "| Maksymalna odległość między kolejnymi punktami [m] | ${plNum(successiveDistances ? successiveDistances.max() : 0)} |"
println "| Średni odstęp czasu między próbkami [s] | ${plNum(mean(intervals))} |"
println "| Mediana odstępu czasu między próbkami [s] | ${plNum(median(intervals))} |"
println "| Średni CPS | ${plNum(mean(cpsValues))} |"
println "| Średni CPM | ${plNum(mean(cpmValues))} |"
println "| Średni poziom µSv/h | ${plNum(meanUsv, 3)} |"
println "| Mediana µSv/h | ${plNum(median(usvValues), 3)} |"
println "| Odchylenie standardowe µSv/h | ${plNum(stdUsv, 3)} |"
println "| Współczynnik zmienności µSv/h [%] | ${plNum(cvUsv, 2)} |"
println "| Minimum µSv/h | ${plNum(usvValues.min(), 3)} |"
println "| Maksimum µSv/h | ${plNum(usvValues.max(), 3)} |"
println "| Percentyl 95 µSv/h | ${plNum(percentile(usvValues, 0.95d), 3)} |"
if (usvMa5) println "| Średnia krocząca 5 s µSv/h - średnia | ${plNum(mean(usvMa5), 3)} |"
if (usvMa10) println "| Średnia krocząca 10 s µSv/h - średnia | ${plNum(mean(usvMa10), 3)} |"
println ''
println '## Tabela do pracy'
println ''
println '| Trasa | Liczba próbek | Czas trwania | Średnia µSv/h | Mediana µSv/h | Odch. stand. µSv/h | Percentyl 95 µSv/h | Średnia odległość od środka [m] | Średnia odległość między kolejnymi punktami [m] |'
println '|---|---|---|---|---|---|---|---|---|'
println "| ${routeName} | ${rows.size()} | ${plDuration(duration)} | ${plNum(meanUsv, 3)} | ${plNum(median(usvValues), 3)} | ${plNum(stdUsv, 3)} | ${plNum(percentile(usvValues, 0.95d), 3)} | ${plNum(mean(distancesToCenter))} | ${plNum(mean(successiveDistances))} |"
