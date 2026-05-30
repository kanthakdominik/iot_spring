#!/usr/bin/env groovy
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

System.setProperty('file.encoding', 'UTF-8')
System.out = new PrintStream(System.out, true, 'UTF-8')
System.err = new PrintStream(System.err, true, 'UTF-8')

if (args.length < 1) {
    System.err.println 'Użycie: groovy route_source_metrics.groovy <plik.csv> [prog_lokalnego_wzrostu_usv] [peak_factor]'
    System.exit(1)
}

def inputFile = new File(args[0])
if (!inputFile.exists()) {
    System.err.println "Nie znaleziono pliku: ${args[0]}"
    System.exit(1)
}

def routeName = inputFile.name
def matcher = routeName =~ /_(\d+)\.csv$/
if (matcher.find()) {
    routeName = matcher.group(1) + " cm"
}

Double localRiseThreshold = 0.15d
Double peakFactor = 0.80d

if (args.length >= 2) {
    localRiseThreshold = args[1].replace(',', '.') as Double
}
if (args.length >= 3) {
    peakFactor = args[2].replace(',', '.') as Double
}

if (peakFactor <= 0d || peakFactor > 1d) {
    System.err.println 'peak_factor musi należeć do przedziału (0, 1].'
    System.exit(1)
}

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
        try {
            def lat = cols[idx.latitude].replace('"', '') as BigDecimal
            def lon = cols[idx.longitude].replace('"', '') as BigDecimal
            rows << [
                    latitude : lat,
                    longitude: lon,
                    timestamp: LocalDateTime.parse(cols[idx.timestamp].replace('"', ''), formatter),
                    cps      : idx.cps >= 0 && cols[idx.cps] ? cols[idx.cps].replace('"', '') as Integer : null,
                    cpm      : idx.cpm >= 0 && cols[idx.cpm] ? cols[idx.cpm].replace('"', '') as Integer : null,
                    usv      : idx.usv >= 0 && cols[idx.usv] ? cols[idx.usv].replace('"', '') as BigDecimal : null,
                    mode     : idx.mode >= 0 ? cols[idx.mode].replace('"', '') : null
            ]
        } catch (Exception ignored) {}
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

def plNum = { Number value, int scale = 2 ->
    if (value == null || (value instanceof Double && value.isNaN())) return ''
    String.format(Locale.US, "% .${scale}f", value).trim().replace('.', ',')
}
def plDate = { LocalDateTime dt -> dt ? dt.format(DateTimeFormatter.ofPattern('dd.MM.yyyy HH:mm:ss')) : '' }
def plDuration = { Duration d ->
    long h = d.toHours()
    long m = d.toMinutesPart()
    long s = d.toSecondsPart()
    h > 0 ? String.format('%02d:%02d:%02d', h, m, s) : String.format('%02d:%02d', m, s)
}

def validGps = rows.findAll { (it.latitude as double) != 0d && (it.longitude as double) != 0d }
List<Double> usvValues = rows.findAll { it.usv != null }.collect { it.usv as double }
List<Double> cpsValues = rows.findAll { it.cps != null }.collect { it.cps as double }
List<Double> cpmValues = rows.findAll { it.cpm != null }.collect { it.cpm as double }

def intervals = []
def validIntervals = []
double totalDistance = 0d
for (int i = 1; i < rows.size(); i++) {
    double dt = Duration.between(rows[i - 1].timestamp, rows[i].timestamp).toMillis() / 1000.0d
    intervals << dt
    if ((rows[i - 1].latitude as double) != 0d && (rows[i - 1].longitude as double) != 0d &&
            (rows[i].latitude as double) != 0d && (rows[i].longitude as double) != 0d) {
        double d = haversine(
                rows[i - 1].latitude as double,
                rows[i - 1].longitude as double,
                rows[i].latitude as double,
                rows[i].longitude as double
        )
        totalDistance += d
        validIntervals << [distance: d, seconds: dt]
    }
}

def duration = Duration.between(rows.first().timestamp, rows.last().timestamp)
double avgSpeed = validIntervals ? (totalDistance / validIntervals.collect { it.seconds }.sum()) * 3.6d : Double.NaN

def movingAvg10 = []
if (usvValues.size() >= 10) {
    movingAvg10 = (0..<(usvValues.size() - 10 + 1)).collect { i -> usvValues.subList(i, i + 10).sum() / 10.0d }
}

def localRiseRows = rows.findAll { it.usv != null && (it.usv as double) >= localRiseThreshold }

def gpsCoverage = rows ? (validGps.size() * 100.0d / rows.size()) : Double.NaN
def longestGap = intervals ? intervals.max() : Double.NaN

def meanUsv = mean(usvValues)
def medUsv = median(usvValues)
def p95Usv = percentile(usvValues, 0.95d)
def stdUsv = stddev(usvValues)

def peakUsvRow = rows.findAll { it.usv != null }.max { it.usv as double }
def peakCpmRow = rows.findAll { it.cpm != null }.max { it.cpm as int }

def maxCpm = cpmValues ? cpmValues.max() : Double.NaN
def peakThreshold = cpmValues ? maxCpm * peakFactor : Double.NaN
def peakRows = cpmValues ? rows.findAll { it.cpm != null && (it.cpm as double) >= peakThreshold } : []
def peakShare = rows ? (peakRows.size() * 100.0d / rows.size()) : Double.NaN

println "# Metryki mobilne i detekcyjne dla trasy `${routeName}`"
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
println "| Liczba próbek z poprawnym GPS | ${validGps.size()} |"
println "| Udział próbek z poprawnym GPS [%] | ${plNum(gpsCoverage, 2)} |"
println "| Średni odstęp czasu między próbkami [s] | ${plNum(mean(intervals))} |"
println "| Mediana odstępu czasu między próbkami [s] | ${plNum(median(intervals))} |"
println "| Najdłuższa przerwa między próbkami [s] | ${plNum(longestGap)} |"
println "| Długość trasy [m] | ${plNum(totalDistance)} |"
println "| Średnia prędkość z GNSS [km/h] | ${plNum(avgSpeed)} |"
println "| Średni CPS | ${plNum(mean(cpsValues))} |"
println "| Średni CPM | ${plNum(mean(cpmValues))} |"
println "| Średni poziom µSv/h | ${plNum(meanUsv, 3)} |"
println "| Mediana µSv/h | ${plNum(medUsv, 3)} |"
println "| Odchylenie standardowe µSv/h | ${plNum(stdUsv, 3)} |"
println "| Minimum µSv/h | ${plNum(usvValues ? usvValues.min() : null, 3)} |"
println "| Maksimum µSv/h | ${plNum(usvValues ? usvValues.max() : null, 3)} |"
println "| Percentyl 95 µSv/h | ${plNum(p95Usv, 3)} |"
println "| Liczba lokalnych wzrostów >= ${plNum(localRiseThreshold, 3)} µSv/h | ${localRiseRows.size()} |"
if (peakUsvRow) println "| Maksimum µSv/h - czas | ${plDate(peakUsvRow.timestamp)} |"
if (peakUsvRow) println "| Maksimum µSv/h - pozycja | ${plNum(peakUsvRow.latitude as double, 6)}, ${plNum(peakUsvRow.longitude as double, 6)} |"
if (movingAvg10) println "| Średnia krocząca 10 s µSv/h - maksimum | ${plNum(movingAvg10.max(), 3)} |"
println ''
println '## Metryki detekcji źródła'
println ''
println '| Metryka | Wartość |'
println '|---|---|'
println "| Maksymalny CPM | ${plNum(maxCpm, 0)} |"
println "| Próg piku = ${plNum(peakFactor * 100, 0)}% max CPM | ${plNum(peakThreshold, 2)} |"
println "| Liczba próbek w strefie piku | ${peakRows.size()} |"
println "| Udział próbek strefy piku [%] | ${plNum(peakShare, 2)} |"
if (peakCpmRow) println "| Czas maksimum CPM | ${plDate(peakCpmRow.timestamp)} |"
if (peakCpmRow) println "| Pozycja maksimum CPM | ${plNum(peakCpmRow.latitude as double, 6)}, ${plNum(peakCpmRow.longitude as double, 6)} |"
println "| Maksimum µSv/h | ${plNum(peakUsvRow?.usv as Double, 3)} |"
if (peakUsvRow) println "| Pozycja maksimum µSv/h | ${plNum(peakUsvRow.latitude as double, 6)}, ${plNum(peakUsvRow.longitude as double, 6)} |"
println ''
println '## Tabela do pracy – mobilność'
println ''
println '| Trasa | Liczba próbek | Czas trwania | Długość trasy [m] | GPS poprawny [%] | Śr. odstęp [s] | Średnia µSv/h | Mediana µSv/h | Percentyl 95 µSv/h | Maksimum µSv/h | Lokalne wzrosty |'
println '|---|---|---|---|---|---|---|---|---|---|---|---|'
println "| ${routeName} | ${rows.size()} | ${plDuration(duration)} | ${plNum(totalDistance)} | ${plNum(gpsCoverage, 2)} | ${plNum(mean(intervals))} | ${plNum(meanUsv, 3)} | ${plNum(medUsv, 3)} | ${plNum(p95Usv, 3)} | ${plNum(usvValues ? usvValues.max() : null, 3)} | ${localRiseRows.size()} |"
println ''
println '## Tabela do pracy – detekcja źródła'
println ''
println '| Trasa | Maksymalny CPM | Próg piku [CPM] | Próbki w strefie piku | Udział strefy piku [%] | Czas maksimum CPM | Pozycja maksimum CPM | Maksimum µSv/h | Pozycja maksimum µSv/h |'
println '|---|---|---|---|---|---|---|---|---|'
println "| ${routeName} | ${plNum(maxCpm, 0)} | ${plNum(peakThreshold, 2)} | ${peakRows.size()} | ${plNum(peakShare, 2)} | ${plDate(peakCpmRow?.timestamp)} | ${peakCpmRow ? plNum(peakCpmRow.latitude as double, 6) + ', ' + plNum(peakCpmRow.longitude as double, 6) : ''} | ${plNum(peakUsvRow?.usv as Double, 3)} | ${peakUsvRow ? plNum(peakUsvRow.latitude as double, 6) + ', ' + plNum(peakUsvRow.longitude as double, 6) : ''} |"
