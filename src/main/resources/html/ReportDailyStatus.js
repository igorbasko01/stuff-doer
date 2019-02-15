document.addEventListener("DOMContentLoaded", function () {
    requestRecordsForToday();
});

// Returns the representation of current midnight in milliseconds.
function getMidnightInMillis(date) {

    var midnight = "T00:00:00.000Z"

    return Date.parse(date + midnight);
}

function getCurrentDateAsString() {
    return new Date().toJSON().slice(0, 10);
}

function getTomorrowDateAsString() {
    var nextDay = new Date();
    nextDay.setDate(nextDay.getDate() + 1);
    return nextDay.toJSON().slice(0, 10);
}

function requestRecordsForToday() {
    var todayMidnight = getMidnightInMillis(getCurrentDateAsString());
    var tomorrowMidnight = getMidnightInMillis(getTomorrowDateAsString());
    requestRecordsForDateRange(todayMidnight, tomorrowMidnight);
}

function requestRecordsForDateRange(startDate_ms, endDate_ms) {
    console.log("Requesting records for date range: "+startDate_ms+" -> "+endDate_ms)
    makeRequest("GET", baseURL + "basched/getAggRecordsByDateRange?from="+startDate_ms+"&to="+endDate_ms)
    .then(function (xhr) {handleReply(xhr);})
    .catch(logHttpError);
}

const sumDurations = (accum, record) => ({duration: parseInt(accum.duration) + parseInt(record.duration)});

function handleReply(response) {
    console.log("Handling records reply now !");
    var records = JSON.parse(response.responseText).records;
    console.log(records);

    if (records.length == 0) {
        console.log("No records yet for today.");
        return;
    }

    var durations = records.reduce(sumDurations);

    displayNumOfPomodoros(durations.duration);
    displayRecords(records, durations.duration);
}

function displayNumOfPomodoros(duration) {
    var pomodorosAmount = duration / 1000 / 60 / 25;
    $("#pomodoros tr").remove();
    $("#pomodoros").append("<tr><th>Date</th><th>Pomodoros</th></tr>");
    $("#pomodoros").append("<tr><td>"+getCurrentDateAsString()+"</td><td>"+pomodorosAmount.toFixed(2)+"</td></tr>");
}

function displayRecords(records, totalDuration) {
    $("#breakdown tr").remove();
    $("#breakdown").append("<tr><th>Task Name</th><th>Duration (Minutes)</th></tr>");

    var tasksRows = records.map(record => {
        var duration = parseInt(record.duration) / 1000 / 60;
        return "<tr><td>"+record.taskName+"</td><td>"+duration.toFixed(2)+"</td></tr>";
    });

    $("#breakdown").append(tasksRows.join(""));

    var sumLineTitle = "<tr><td></td><td><b>Total<b></td></tr>";
    var durationInMinutes = totalDuration / 1000 / 60;
    var sumLine = "<tr><td></td><td>"+durationInMinutes.toFixed(2)+"</td></tr>";

    $("#breakdown").append(sumLineTitle);
    $("#breakdown").append(sumLine);
}
