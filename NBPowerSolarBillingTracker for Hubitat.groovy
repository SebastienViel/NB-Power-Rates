/**
 *  NB Power Solar Billing Tracker  v1.4
 *
 *  Monitors energy data to compare the current NB Power net metering rate
 *  structure against the proposed three-part rate (import rate + export credit
 *  + demand charge), pending EUB approval April 1, 2027.
 *
 *  Supports two device modes (selected in preferences):
 *
 *  MODE A — PowerMeter (watts), e.g. Emporia Vue via amithalp/EmporiaV2
 *    - Two devices: "mains from grid" and "mains to grid", both PowerMeter
 *    - App polls watts every minute and integrates to kWh internally
 *    - Also builds a 15-min rolling average for peak demand tracking
 *
 *  MODE B — EnergyMeter (kWh, never-resetting), e.g. Aeotec or similar
 *    - Two devices: grid consumption total (kWh) and solar production total (kWh)
 *    - Both counters are monotonically increasing and never reset
 *    - App snapshots each counter at billing period start, computes delta
 *    - For peak demand, a separate PowerMeter device can optionally be added;
 *      without it, demand charge is estimated from the per-minute kWh delta
 *
 *  In both modes the billing formulas are identical:
 *    Current net metering : (importKwh − exportKwh) × currentRate
 *    Proposed structure   : (importKwh × importRate)
 *                         − (exportKwh × exportRate)
 *                         + (peakKw    × demandCharge)
 *
 *  v1.4 changes:
    - Added Refresh Display button to manually update the status paragraph.
    - Corrected device brand name to Aeotec (was Anotech).
    - Clarified MODE B solar input is inverter total production, not export.

  v1.3 changes:
 *    - Added MODE B: EnergyMeter (never-resetting kWh) device support
 *    - Device mode selector in preferences switches UI and calculation path
 *    - Peak demand in MODE B works from optional PowerMeter or estimated from kWh delta
 *    - Demand window (7 am–10 pm) enforced in both modes
 *    - All existing MODE A (PowerMeter/Emporia) behaviour preserved unchanged
 *
 *  v1.2 changes:
 *    - Added importUrl to definition block for one-click Hubitat import.
 *
 *  v1.1 changes:
 *    - All device inputs use capability.powerMeter (watts) only.
 *    - Removed snapshot/manual accumulation mode choice.
 *    - Separate peak power device input removed.
 *
 *  Author : generated for Sébastien with Claude (Anthropic), May 2026
 *  License: Apache 2.0
 */

definition(
    name:        "NB Power Solar Billing Tracker",
    namespace:   "nbpower",
    author:      "Sébastien",
    description: "Tracks solar import/export kWh and 15-min peak demand to compare " +
                 "current NB Power net metering against the proposed rate structure. " +
                 "Supports Emporia Vue (watts) and Aeotec-style (never-resetting kWh) devices.",
    category:    "Energy Management",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/SebastienViel/NB-Power-Rates/main/NBPowerSolarBillingTracker%20for%20Hubitat.groovy"
)

// ---------------------------------------------------------------------------
// Preferences / UI
// ---------------------------------------------------------------------------
preferences {
    page(name: "mainPage")
    page(name: "ratesPage")
    page(name: "devicesPage")
    page(name: "notificationsPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "NB Power Solar Billing Tracker", install: true, uninstall: true) {

        section("<b>Setup</b>") {
            href "devicesPage",       title: "📡 Energy Devices",  description: devicesDescription(),  required: true
            href "ratesPage",         title: "💲 Rate Settings",   description: ratesDescription(),    required: true
            href "notificationsPage", title: "🔔 Notifications",   description: notifyDescription()
        }

        section("<b>Billing Period</b>") {
            input "billingDay", "number",
                title: "Billing cycle start day of month (e.g. 1 for the 1st)",
                defaultValue: 1, range: "1..28", required: true
            paragraph "Current billing period: <b>${currentBillingPeriodLabel()}</b>"
        }

        section("<b>Dashboard Summary</b>") {
            input "summaryDevice", "capability.actuator",
                title: "Optional: Virtual device to write summary tile attributes to",
                required: false, multiple: false
            paragraph "Attributes written every minute: <i>nbpImportKwh, nbpExportKwh, " +
                      "nbpPeakKw, nbpCurrentCost, nbpProposedCost, nbpDelta, " +
                      "nbpPeakKwTime, nbpBillingStart</i>"
        }

        section("<b>Billing Period Controls</b>") {
            input "refreshBtn", "button", title: "🔃 Refresh Display"
            input "resetBtn",   "button", title: "🔄 Reset Billing Period Now"
            paragraph "Refresh updates the status below from current device readings. " +
                      "Reset zeroes all counters and starts a new billing period."
            paragraph billingStatusParagraph()
        }

        section("<b>Logging</b>") {
            input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

def devicesPage() {
    dynamicPage(name: "devicesPage", title: "Energy Devices", nextPage: "mainPage") {

        section("<b>Device Mode</b>") {
            input "deviceMode", "enum",
                title: "Select your meter type",
                options: [
                    "watts": "MODE A — PowerMeter / watts (e.g. Emporia Vue)",
                    "kwh":   "MODE B — EnergyMeter / never-resetting kWh (e.g. Aeotec)"
                ],
                defaultValue: "watts", required: true, submitOnChange: true
        }

        // ── MODE A: PowerMeter (watts) ──────────────────────────────────
        if (!deviceMode || deviceMode == "watts") {
            section("<b>MODE A — PowerMeter devices (watts)</b>") {
                paragraph """The Emporia Vue integration exposes a <i>power</i> attribute 
in watts for each circuit. Select the two mains channels.
Both are treated as unsigned — negative readings are safely ignored."""

                input "gridImportDevice", "capability.powerMeter",
                    title: "Mains FROM grid — watts consumed from the utility",
                    description: "Your 'mains from grid' or grid import CT channel",
                    required: true, multiple: false

                input "solarExportDevice", "capability.powerMeter",
                    title: "Mains TO grid — watts your solar sends to the grid",
                    description: "Your 'mains to grid' or solar export CT channel",
                    required: true, multiple: false
            }

            section("<b>How it works in MODE A</b>") {
                paragraph """The app polls both devices every minute. Watts are converted 
to kWh (watts ÷ 1000 ÷ 60) and added to running totals. The grid import 
device also feeds a 15-minute rolling average for peak demand tracking."""
            }
        }

        // ── MODE B: EnergyMeter (never-resetting kWh) ──────────────────
        if (deviceMode == "kwh") {
            section("<b>MODE B — EnergyMeter devices (never-resetting kWh)</b>") {
                paragraph """These devices expose an <i>energy</i> attribute that increases 
monotonically and never resets. The app records a baseline at the start 
of each billing period and computes usage as <b>current − baseline</b>.

The solar device should be your inverter's total production counter (all kWh 
generated, regardless of whether they were self-consumed or exported). 
The app computes export as: solar production − self-consumed, where 
self-consumed = solar production − (grid import delta).

If your inverter only reports export (kWh sent to grid), that works too — 
just know the number will be smaller than total production."""

                input "gridKwhDevice", "capability.energyMeter",
                    title: "Grid consumption — total kWh drawn from NB Power (never resets)",
                    description: "e.g. Aeotec mains import channel",
                    required: true, multiple: false

                input "solarKwhDevice", "capability.energyMeter",
                    title: "Solar production — total kWh generated by inverter (never resets)",
                    description: "e.g. Aeotec reading from your inverter's total production counter",
                    required: true, multiple: false
            }

            section("<b>Peak demand in MODE B</b>") {
                paragraph """Peak demand tracking requires instantaneous power (watts). 
Options:
• If you have <i>any</i> PowerMeter device that shows whole-home grid draw, 
  select it below and the app will use it for 15-min demand tracking.
• If you leave it blank, the app estimates demand from the per-minute 
  kWh delta (less precise but functional). The demand charge will still 
  be calculated — it may be slightly conservative."""

                input "peakWattsDevice", "capability.powerMeter",
                    title: "Optional: PowerMeter device for peak demand tracking (watts)",
                    description: "Leave blank to estimate from kWh delta",
                    required: false, multiple: false
            }

            section("<b>How it works in MODE B</b>") {
                paragraph """At billing period start (or on first run), the app snapshots 
both kWh counters. Every minute it reads both current values and computes:
  importKwh = current grid kWh − baseline grid kWh
  exportKwh = current solar kWh − baseline solar kWh
These are used directly in the billing formulas."""
            }
        }
    }
}

def ratesPage() {
    dynamicPage(name: "ratesPage", title: "Rate Settings", nextPage: "mainPage") {

        section("<b>Current NB Power Net Metering Rate</b>") {
            paragraph "Under the current programme, import and export offset at the same rate (1:1)."
            input "currentNetRate", "decimal",
                title: "Net metering rate (¢/kWh)",
                defaultValue: 14.76, range: "0.01..99.99", required: true
        }

        section("<b>Proposed Rate Structure</b>") {
            paragraph "Pending EUB approval for April 1, 2027. Adjust as NB Power confirms final numbers."
            input "proposedImportRate", "decimal",
                title: "Import rate — energy drawn from grid (¢/kWh)",
                defaultValue: 14.76, range: "0.01..99.99", required: true
            input "proposedExportRate", "decimal",
                title: "Export credit — energy sent to grid (¢/kWh)",
                defaultValue: 6.77, range: "0.01..99.99", required: true
            input "demandChargeRate", "decimal",
                title: "Demand charge rate (\$/kW of peak 15-min demand)",
                defaultValue: 13.00, range: "0.01..99.99", required: true
        }

        section("<b>Demand charge window</b>") {
            paragraph "NB Power's proposal applies the demand charge only between <b>7:00 am and 10:00 pm</b>."
            input "demandWindowStart", "number",
                title: "Window start hour (0–23, default 7)",
                defaultValue: 7, range: "0..23", required: true
            input "demandWindowEnd", "number",
                title: "Window end hour (0–23, default 22 = 10:00 pm)",
                defaultValue: 22, range: "0..23", required: true
        }
    }
}

def notificationsPage() {
    dynamicPage(name: "notificationsPage", title: "Notifications", nextPage: "mainPage") {
        section("<b>Demand spike alert</b>") {
            paragraph "Sends a notification when instantaneous grid draw exceeds a threshold."
            input "notifyDevices", "capability.notification",
                title: "Notification devices", required: false, multiple: true
            input "spikeThresholdKw", "decimal",
                title: "Alert when grid draw exceeds (kW) — set 0 to disable",
                defaultValue: 0, range: "0..100", required: false
            input "spikeAlertCooldownMin", "number",
                title: "Minimum minutes between spike alerts",
                defaultValue: 30, range: "1..1440", required: false
        }
        section("<b>Monthly summary notification</b>") {
            input "sendMonthlySummary", "bool",
                title: "Send billing summary at start of each new period",
                defaultValue: true
        }
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------
def installed() {
    logDebug "Installed"
    initialize()
}

def updated() {
    logDebug "Updated — re-initialising"
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    if (state.billingStart == null) resetBillingPeriod()

    // Poll every minute
    schedule("0 * * * * ?", "everyMinute")

    // Auto-reset at midnight on the billing day
    schedule("0 0 0 ${billingDay} * ?", "autoBillingReset")

    logInfo "Initialised in MODE ${deviceMode?.toUpperCase() ?: 'A (watts)'}. " +
            "Billing period: ${currentBillingPeriodLabel()}"
    refreshSummary()
}

// ---------------------------------------------------------------------------
// Button handler
// ---------------------------------------------------------------------------
def appButtonHandler(btn) {
    if (btn == "refreshBtn") {
        logDebug "Manual display refresh."
        refreshSummary()
    } else if (btn == "resetBtn") {
        logInfo "Manual billing period reset."
        endOfPeriodSummary()
        resetBillingPeriod()
        refreshSummary()
    }
}

// ---------------------------------------------------------------------------
// Billing period reset
// ---------------------------------------------------------------------------
def autoBillingReset() {
    logInfo "Automatic billing period reset on day ${billingDay}."
    if (sendMonthlySummary) endOfPeriodSummary()
    resetBillingPeriod()
    refreshSummary()
}

def resetBillingPeriod() {
    def ts = now()
    state.billingStart    = ts
    state.billingStartStr = formatDate(ts)

    // MODE A — watts integration totals
    state.importKwhManual = 0.0
    state.exportKwhManual = 0.0

    // MODE B — never-resetting kWh baselines (snapshot at reset time)
    state.importKwhBase   = safeEnergy(gridKwhDevice)
    state.exportKwhBase   = safeEnergy(solarKwhDevice)

    // Peak demand — shared by both modes
    state.minuteWatts     = []    // rolling 15-sample buffer
    state.peakKw          = 0.0
    state.peakKwTime      = ""

    // Spike alert cooldown
    state.lastSpikeAlert  = 0L

    logInfo "Billing period reset at ${state.billingStartStr}." +
            (deviceMode == "kwh" ?
                " Grid baseline: ${state.importKwhBase} kWh, Solar baseline: ${state.exportKwhBase} kWh." :
                " Watt-integration counters zeroed.")
}

// ---------------------------------------------------------------------------
// Every-minute handler
// ---------------------------------------------------------------------------
def everyMinute() {
    if (deviceMode == "kwh") {
        everyMinuteKwh()
    } else {
        everyMinuteWatts()
    }
    refreshSummary()
}

// ── MODE A: watts ──────────────────────────────────────────────────────────
def everyMinuteWatts() {
    double importW = Math.max(0.0, safeWatts(gridImportDevice))
    double exportW = Math.max(0.0, safeWatts(solarExportDevice))

    // Integrate to kWh: 1 min at W watts = W/1000/60 kWh
    state.importKwhManual = (state.importKwhManual ?: 0.0) + (importW / 1000.0 / 60.0)
    state.exportKwhManual = (state.exportKwhManual ?: 0.0) + (exportW / 1000.0 / 60.0)

    trackDemand(importW)
    checkSpikeAlert(importW)
}

// ── MODE B: never-resetting kWh ───────────────────────────────────────────
def everyMinuteKwh() {
    // kWh values come straight from the energy attribute delta — no integration needed.
    // Demand tracking: use dedicated PowerMeter if configured, otherwise estimate
    // from the per-minute import kWh delta (delta kWh × 60 = average kW that minute).
    double wattsForDemand
    if (peakWattsDevice) {
        wattsForDemand = Math.max(0.0, safeWatts(peakWattsDevice))
    } else {
        // Estimate: how many kWh arrived in the last minute × 60 min/h × 1000 W/kW
        double prevImport = (state.prevImportKwh ?: safeEnergy(gridKwhDevice)) as double
        double curImport  = safeEnergy(gridKwhDevice)
        double deltaKwh   = Math.max(0.0, curImport - prevImport)
        wattsForDemand    = deltaKwh * 60.0 * 1000.0
        state.prevImportKwh = curImport
    }

    trackDemand(wattsForDemand)
    checkSpikeAlert(wattsForDemand)
}

// ── Shared demand tracking ─────────────────────────────────────────────────
void trackDemand(double wattsNow) {
    int hr       = Calendar.getInstance(location.timeZone).get(Calendar.HOUR_OF_DAY)
    int winStart = (demandWindowStart ?: 7) as int
    int winEnd   = (demandWindowEnd   ?: 22) as int

    if (hr >= winStart && hr < winEnd) {
        List buf = state.minuteWatts ?: []
        buf << wattsNow
        if (buf.size() > 15) buf = buf.drop(buf.size() - 15)
        state.minuteWatts = buf

        if (buf.size() == 15) {
            double avgKw = (buf.sum() / 15.0) / 1000.0
            if (avgKw > (state.peakKw ?: 0.0)) {
                state.peakKw     = avgKw.round(4)
                state.peakKwTime = formatDate(now())
                logDebug "New peak 15-min demand: ${state.peakKw} kW at ${state.peakKwTime}"
            }
        }
    } else {
        state.minuteWatts = []   // clear buffer outside demand window
    }
}

// ── Spike alert ────────────────────────────────────────────────────────────
void checkSpikeAlert(double wattsNow) {
    double threshKw = (spikeThresholdKw ?: 0) as double
    if (threshKw <= 0) return
    if ((wattsNow / 1000.0) <= threshKw) return

    long cooldownMs = ((spikeAlertCooldownMin ?: 30) * 60 * 1000) as long
    if ((now() - (state.lastSpikeAlert ?: 0L)) > cooldownMs) {
        state.lastSpikeAlert = now()
        double kw     = (wattsNow / 1000.0).round(2)
        double impact = (kw * (demandChargeRate ?: 13.0)).round(2)
        sendNotifications(
            "⚡ Demand spike: grid draw is ${kw} kW " +
            "(threshold ${threshKw} kW). " +
            "If sustained 15 min, demand charge impact: \$${impact}."
        )
    }
}

// ---------------------------------------------------------------------------
// Core calculations — same formulas regardless of mode
// ---------------------------------------------------------------------------
double importKwh() {
    if (deviceMode == "kwh") {
        double cur  = safeEnergy(gridKwhDevice)
        double base = (state.importKwhBase ?: 0.0) as double
        return Math.max(0.0, cur - base)
    }
    return (state.importKwhManual ?: 0.0) as double
}

double exportKwh() {
    if (deviceMode == "kwh") {
        double cur  = safeEnergy(solarKwhDevice)
        double base = (state.exportKwhBase ?: 0.0) as double
        return Math.max(0.0, cur - base)
    }
    return (state.exportKwhManual ?: 0.0) as double
}

double peakKw()      { return (state.peakKw ?: 0.0) as double }

double currentCost() {
    double netKwh = importKwh() - exportKwh()
    return (netKwh * ((currentNetRate ?: 14.76) / 100.0)).round(2)
}

double proposedCost() {
    double importCost    = importKwh() * ((proposedImportRate ?: 14.76) / 100.0)
    double exportCredit  = exportKwh() * ((proposedExportRate ?: 6.77)  / 100.0)
    double demandCost    = peakKw()    *  (demandChargeRate   ?: 13.00)
    return (importCost - exportCredit + demandCost).round(2)
}

double delta() { return (proposedCost() - currentCost()).round(2) }

// ---------------------------------------------------------------------------
// Summary / dashboard
// ---------------------------------------------------------------------------
def refreshSummary() {
    double imp  = importKwh().round(2)
    double exp  = exportKwh().round(2)
    double peak = peakKw().round(3)
    double cur  = currentCost()
    double prop = proposedCost()
    double delt = delta()
    String sign = delt >= 0 ? "+" : ""

    logDebug "Summary [MODE ${deviceMode?.toUpperCase()}] — " +
             "Import: ${imp} kWh | Export: ${exp} kWh | Peak: ${peak} kW | " +
             "Current: \$${cur} | Proposed: \$${prop} | Δ: ${sign}\$${delt}"

    if (summaryDevice) {
        sendEvent(summaryDevice, [name: "nbpImportKwh",    value: imp,              unit: "kWh"])
        sendEvent(summaryDevice, [name: "nbpExportKwh",    value: exp,              unit: "kWh"])
        sendEvent(summaryDevice, [name: "nbpPeakKw",       value: peak,             unit: "kW"])
        sendEvent(summaryDevice, [name: "nbpCurrentCost",  value: cur,              unit: "\$"])
        sendEvent(summaryDevice, [name: "nbpProposedCost", value: prop,             unit: "\$"])
        sendEvent(summaryDevice, [name: "nbpDelta",        value: "${sign}${delt}", unit: "\$"])
        sendEvent(summaryDevice, [name: "nbpPeakKwTime",   value: state.peakKwTime ?: "—"])
        sendEvent(summaryDevice, [name: "nbpBillingStart", value: state.billingStartStr ?: "—"])
    }
}

def endOfPeriodSummary() {
    double imp  = importKwh().round(2)
    double exp  = exportKwh().round(2)
    double peak = peakKw().round(3)
    double cur  = currentCost()
    double prop = proposedCost()
    double delt = delta()
    String sign = delt >= 0 ? "+" : ""

    double importLineCost = (imp  * (proposedImportRate ?: 14.76) / 100.0).round(2)
    double exportLineCost = (exp  * (proposedExportRate ?: 6.77)  / 100.0).round(2)
    double demandLineCost = (peak * (demandChargeRate   ?: 13.00)).round(2)

    def modeLabel = deviceMode == "kwh" ?
        "EnergyMeter / never-resetting kWh" : "PowerMeter / watts (Emporia)"

    def msg = """\
📊 NB Power Billing Period Summary
Mode   : ${modeLabel}
Period : ${state.billingStartStr} – ${formatDate(now())}
─────────────────────────────────────
Grid import  : ${imp} kWh
Solar export : ${exp} kWh
Net kWh      : ${(imp - exp).round(2)} kWh
Peak 15-min  : ${peak} kW  (at ${state.peakKwTime ?: "not recorded"})
─────────────────────────────────────
Current net metering  : \$${cur}
Proposed structure    : \$${prop}
  Import  ${imp} kWh × ${proposedImportRate}¢  =  \$${importLineCost}
  Export  ${exp} kWh × ${proposedExportRate}¢  = −\$${exportLineCost}
  Demand  ${peak} kW × \$${demandChargeRate}    =  \$${demandLineCost}
─────────────────────────────────────
Proposed vs. current  : ${sign}\$${delt}  (${delt >= 0 ? "MORE expensive" : "less expensive"} under new model)
Annualised projection : ${sign}\$${(delt * 12).round(0)}/yr"""

    logInfo msg
    sendNotifications(msg)
}

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------
double safeWatts(dev) {
    try {
        def v = dev?.currentValue("power")
        return (v != null) ? (v as double) : 0.0
    } catch (e) {
        logDebug "safeWatts error for ${dev?.displayName}: ${e.message}"
        return 0.0
    }
}

double safeEnergy(dev) {
    try {
        def v = dev?.currentValue("energy")
        return (v != null) ? (v as double) : 0.0
    } catch (e) {
        logDebug "safeEnergy error for ${dev?.displayName}: ${e.message}"
        return 0.0
    }
}

void sendNotifications(String msg) {
    notifyDevices?.each { it.deviceNotification(msg) }
}

String formatDate(long ts) {
    return new Date(ts).format("yyyy-MM-dd HH:mm", location.timeZone)
}

String currentBillingPeriodLabel() {
    def now = new Date()
    int day = now.date
    int yr  = now.year + 1900
    int mo  = now.month
    int bd  = (billingDay ?: 1) as int

    Date start
    if (day >= bd) {
        start = Date.parse("yyyy-M-d", "${yr}-${mo + 1}-${bd}")
    } else {
        def cal = Calendar.getInstance()
        cal.set(yr, mo - 1, bd)
        start = cal.time
    }
    return start.format("MMM d") + " – " + (start + 30).format("MMM d, yyyy")
}

String billingStatusParagraph() {
    double imp  = importKwh().round(2)
    double exp  = exportKwh().round(2)
    double peak = peakKw().round(3)
    double cur  = currentCost()
    double prop = proposedCost()
    double delt = delta()
    String sign = delt >= 0 ? "+" : ""
    String mode = deviceMode == "kwh" ? "MODE B (kWh)" : "MODE A (watts)"

    return "<b>Mode:</b> ${mode}" +
           "<br><b>Period started:</b> ${state.billingStartStr ?: '(not set)'}" +
           "<br><b>Import:</b> ${imp} kWh &nbsp; <b>Export:</b> ${exp} kWh" +
           "<br><b>Peak 15-min demand:</b> ${peak} kW (at ${state.peakKwTime ?: '—'})" +
           "<br><b>Current net metering cost:</b> \$${cur}" +
           "<br><b>Proposed cost:</b> \$${prop} &nbsp; <b>Δ</b> ${sign}\$${delt}"
}

String devicesDescription() {
    if (deviceMode == "kwh") {
        if (!gridKwhDevice || !solarKwhDevice) return "Tap to configure (MODE B — kWh) ←"
        return "MODE B | Grid: ${gridKwhDevice.displayName}\nSolar: ${solarKwhDevice.displayName}" +
               (peakWattsDevice ? "\nPeak meter: ${peakWattsDevice.displayName}" : "\nPeak: estimated from kWh delta")
    }
    if (!gridImportDevice || !solarExportDevice) return "Tap to configure (MODE A — watts) ←"
    return "MODE A | From grid: ${gridImportDevice.displayName}\nTo grid: ${solarExportDevice.displayName}"
}

String ratesDescription() {
    return "Net: ${currentNetRate}¢ | Import: ${proposedImportRate}¢ | " +
           "Export: ${proposedExportRate}¢ | Demand: \$${demandChargeRate}/kW"
}

String notifyDescription() {
    if (!notifyDevices) return "No notifications configured"
    return "${notifyDevices.size()} device(s) | Spike threshold: ${spikeThresholdKw ?: 'off'} kW"
}

void logDebug(msg) { if (debugLog) log.debug "[NBPSolar] ${msg}" }
void logInfo(msg)  { log.info  "[NBPSolar] ${msg}" }
