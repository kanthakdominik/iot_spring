#!/usr/bin/env groovy
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

if (args.length < 1) {
    System.err.println "Użycie: groovy gps_metrics.groovy <plik.csv> [nazwa_trasy]"
    System.exit(1)
}

def inputFile = new File(args[0])
if (!inputFile.exists()) {
    System.err.println "Nie znaleziono pliku: ${args[0]}"
    System.exit(1)
}

def routeName = args.length > 1 ? args[1] : inputFile.name

def formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
def rows = []

def parseCsvLine = { String line ->
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
    def header = parseCsvLine(reader.readLine())
    def indexes = [
            latitude : header.indexOf('"latitude"') >= 0 ? header.indexOf('"latitude"') : header.indexOf('latitude'),
            longitude: header.indexOf('"longitude"') >= 0 ? header.indexOf('"longitude"') : header.indexOf('longitude'),
            timestamp: header.indexOf('"timestamp"') >= 0 ? header.indexOf('"timestamp"') : header.indexOf('timestamp'),
            cps      : header.indexOf('"cps"') >= 0 ? header.indexOf('"cps"') : header.indexOf('cps'),
            cpm      : header.indexOf('"cpm"') >= 0 ? header.indexOf('"cpm"') : header.indexOf('cpm'),
            usv      : header.indexOf('"usv_per_hr"') >= 0 ? header.indexOf('"usv_per_hr"') : header.indexOf('usv_per_hr')
    ]

    reader.eachLine { line ->
        if (!line?.trim()) return
        def cols = parseCsvLine(line)
        rows << [
                latitude : cols[indexes.latitude].replace('"', '') as BigDecimal,
                longitude: cols[indexes.longitude].replace('"', '') as BigDecimal,
                timestamp: LocalDateTime.parse(cols[indexes.timestamp].replace('"', ''), formatter),
                cps      : indexes.cps >= 0 ? cols[indexes.cps].replace('"', '') as Integer : null,
                cpm      : indexes.cpm >= 0 ? cols[indexes.cpm].replace('"', '') as Integer : null,
                usv      : indexes.usv >= 0 ? cols[indexes.usv].replace('"', '') as BigDecimal : null
        ]
    }
}

if (rows.isEmpty()) {
    System.err.println "Brak danych w pliku CSV"
    System.exit(1)
}

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

double meanLat = rows.collect { it.latitude as double }.sum() / rows.size()
double meanLon = rows.collect { it.longitude as double }.sum() / rows.size()

def distances = rows.collect { row -> haversine(row.latitude as double, row.longitude as double, meanLat, meanLon) }

def successiveDistances = []
for (int i = 1; i < rows.size(); i++) {
    successiveDistances << haversine(
            rows[i - 1].latitude as double,
            rows[i - 1].longitude as double,
            rows[i].latitude as double,
            rows[i].longitude as double
    )
}

def sortedDistances = distances.sort()
int idx95 = Math.ceil(sortedDistances.size() * 0.95d) as int
idx95 = Math.max(1, Math.min(idx95, sortedDistances.size())) - 1

def latValues = rows.collect { it.latitude as double }
def lonValues = rows.collect { it.longitude as double }
def minLat = latValues.min()
def maxLat = latValues.max()
def minLon = lonValues.min()
def maxLon = lonValues.max()

def duration = Duration.between(rows.first().timestamp, rows.last().timestamp)

def avgCpm = rows.findAll { it.cpm != null }.collect { it.cpm as double }
def avgUsv = rows.findAll { it.usv != null }.collect { it.usv as double }

println "=== METRYKI GPS DLA TRASY: ${routeName} ==="
println "Liczba próbek: ${rows.size()}"
println "Czas początku: ${rows.first().timestamp}"
println "Czas końca: ${rows.last().timestamp}"
println "Czas trwania [s]: ${duration.seconds}"
println "Środek klastra (lat, lon): ${String.format(java.util.Locale.US, '%.6f', meanLat)}, ${String.format(java.util.Locale.US, '%.6f', meanLon)}"
println "Zakres latitude: ${String.format(java.util.Locale.US, '%.6f', minLat)} .. ${String.format(java.util.Locale.US, '%.6f', maxLat)}"
println "Zakres longitude: ${String.format(java.util.Locale.US, '%.6f', minLon)} .. ${String.format(java.util.Locale.US, '%.6f', maxLon)}"
println "Średnia odległość od środka [m]: ${String.format(java.util.Locale.US, '%.2f', distances.sum() / distances.size())}"
println "Maksymalna odległość od środka [m]: ${String.format(java.util.Locale.US, '%.2f', distances.max())}"
println "Percentyl 95 odległości od środka [m]: ${String.format(java.util.Locale.US, '%.2f', sortedDistances[idx95])}"
if (!successiveDistances.isEmpty()) {
    println "Średnia odległość między kolejnymi punktami [m]: ${String.format(java.util.Locale.US, '%.2f', successiveDistances.sum() / successiveDistances.size())}"
    println "Maksymalna odległość między kolejnymi punktami [m]: ${String.format(java.util.Locale.US, '%.2f', successiveDistances.max())}"
}
if (!avgCpm.isEmpty()) {
    println "Średni CPM: ${String.format(java.util.Locale.US, '%.2f', avgCpm.sum() / avgCpm.size())}"
}
if (!avgUsv.isEmpty()) {
    println "Średni poziom µSv/h: ${String.format(java.util.Locale.US, '%.3f', avgUsv.sum() / avgUsv.size())}"
}

println "\n=== TABELA DO PRACY ==="
println "Trasa;Liczba próbek;Czas trwania [s];Średnia odległość od środka [m];Maksymalna odległość od środka [m];Percentyl 95 [m];Średnia odległość między kolejnymi punktami [m]"
println "${routeName};${rows.size()};${duration.seconds};${String.format(java.util.Locale.US, '%.2f', distances.sum() / distances.size())};${String.format(java.util.Locale.US, '%.2f', distances.max())};${String.format(java.util.Locale.US, '%.2f', sortedDistances[idx95])};${successiveDistances.isEmpty() ? '' : String.format(java.util.Locale.US, '%.2f', successiveDistances.sum() / successiveDistances.size())}"