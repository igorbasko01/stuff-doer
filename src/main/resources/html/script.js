var timer;
var timeEnd;

var intervalToUpdate_ms = 10 * 1000;
var intervalEnd;

var priority = ["Immediate", "High", "Regular"];

var currentTask;

// request permission on page load
document.addEventListener('DOMContentLoaded', function () {
  if (Notification.permission !== "granted")
    Notification.requestPermission();

  requestUnfinishedTasks();
});

function notifyMe() {
  if (!Notification) {
    alert('Desktop notifications not available in your browser. Try Chromium.'); 
    return;
  }

  if (Notification.permission !== "granted")
    Notification.requestPermission();
  else {
    var notification = new Notification('Notification title', {
//      icon: 'http://cdn.sstatic.net/stackexchange/img/logos/so/so-icon.png',
      body: "Timer Ended !",
    });

    var audio = new Audio("resources/mp3/Alarm.mp3");
    audio.play();

    notification.onclick = function () {
        window.location.href = 'http://localhost:9080/html/index.html';
//      window.open("http://localhost:9080/html/index.html");
    };
  }

}

function toggleStartStopButton(currentTime = new Date().getTime()) {
    console.log("toggleStartStopButton");
    var btnStart = $("#startTaskBtn");
    var btnState = btnStart.text();
    var promise = Promise.resolve();
    if (btnState == "Start") {
        startTimer();
        btnStart.text("Stop");
    } else {
        promise = stopTimer(currentTime);
        btnStart.text("Start");
    }

    return promise;
}

function setStartStopButtonState(newState) {
    var btnStart = $("#startTaskBtn");
    var btnState = btnStart.text();
    // If the states are equal it means that currently the button displays the state that we want to change to.
    // For example, currently the text of the button is "Stop" because the task is running, and we want to go to
    // "Stop" state, so it has the same text.
    if (btnState == newState)
        toggleStartStopButton();
}

function startTimer() {
    getRemainingTime(resetIntervals);
}

function resetIntervals(pomodoroDuration) {
    var currentTime = new Date().getTime();
    timeEnd = currentTime + pomodoroDuration;

    timer = setInterval(timerEnds, 1000);

    resetCommitInterval(currentTime);
}

function stopTimer(currentTime) {
    clearInterval(timer);
    return commitRecord(currentTime);
}

// Sets when the commit interval should happen.
function resetCommitInterval(currentTime) {
    intervalEnd = currentTime + intervalToUpdate_ms;
}

// Checks if the timer ended. If ended notifies the user and stops the interval.
function timerEnds() {
    var currentTime = new Date().getTime();
    if (currentTime > timeEnd) {
        notifyMe();
        toggleStartStopButton(currentTime)
        .then(function () { return updatePomodoros(currentTask.id, 1); })
        .then(function () { return updateTasksWindow(currentTask.id); })
        .then(function () { requestUnfinishedTasks(); });
        timeEnd = currentTime;
    } else {
        // If the timer ends, avoid duplicate record commit.
        handleCommitInterval(currentTime);
    }

    displayTime(timeEnd - currentTime, $("#time"));
}

// Checks if an interval passed and commits the work to the server.
function handleCommitInterval(currentTime) {
    if (currentTime > intervalEnd) {
        commitRecord(currentTime);
        resetCommitInterval(currentTime);
    }

    displayTime(intervalEnd - currentTime, $("#intervalTime"));
}

// It means that it adds a row to the RECORDS table.
function commitRecord(currentTime) {

    var taskid = currentTask.id;
    var timestamp = currentTime;
    // Calculate how much time the duration of the interval was.
    // The max length of an interval without the part of the time that passed.
    var duration = intervalToUpdate_ms - Math.max(0, intervalEnd - currentTime);
    var roundedDuration = Math.round(duration/1000)*1000;

    return makeRequest('POST',
        "http://localhost:9080/basched/addRecord?taskid="+taskid+"&timestamp="+timestamp+"&duration="+roundedDuration)
        .then(handleRecordCommitResponse)
        .catch(logHttpError);
}

function handleRecordCommitResponse(responseObject) {
    if (responseObject.readyState == 4 && responseObject.status == 201) {
        console.log("Record Committed !");
    } else if (responseObject.readyState == 4) {
        console.log("Could not commit record !");
    }
}

// Display the remaining pomodoro time in a pretty way :)
function displayTime(timeToDisplay, domObject = $("#time")) {
    var minutesRemaining = Math.floor(timeToDisplay / 1000 / 60);
    var secondsRemaining = Math.floor((timeToDisplay / 1000) - (minutesRemaining * 60));

    var mintsToDisp = (minutesRemaining < 10) ? "0" + minutesRemaining : minutesRemaining;
    var scndsToDisp = (secondsRemaining < 10) ? "0" + secondsRemaining : secondsRemaining;

    domObject.text(mintsToDisp + ":" + scndsToDisp);
}

function gotoAddTaskPage() {
    window.location.href = 'http://localhost:9080/html/AddTask.html';
}

function createHoldButton(task) {
    var buttonText = (task.status == 0 || task.status == 1) ? "HOLD" : "RELEASE";
    return "<button id=hold_"+task.id+" onclick=toggleHold("+task.id+")>"+buttonText+"</button>";
}

function toggleHold(id) {
    // Stop the timer if the current task was chosen to hold.
    if (currentTask != null && id == currentTask.id)
        setStartStopButtonState("Stop");

    makeRequest('POST', "http://localhost:9080/basched/toggleHold?taskid="+id)
        .then(requestUnfinishedTasks)
        .catch(logHttpError);
}

function handleTasksReply(response) {
    console.log("Unfinished task reply handling now.")
    $("#tasks_table tr").remove();
    $("#current_task tr").remove();
    $("#current_task").append("<tr><th>Project</th><th>Current Task</th><th>Priority</th></tr>}");
    $("#tasks_table").append("<tr><th>Project</th><th>Other Tasks</th><th>Priority</th></tr>")
    var tasks = JSON.parse(response.responseText).tasks;
    var tasksRows = [];
    var current_task = ""
    currentTask = null;
    for (var i = 0; i < tasks.length; i++) {
        var taskName = tasks[i].name;
        var prjName = tasks[i].prjName;
        var taskPri = priority[tasks[i].priority];
        var button_finished = "<button id=finished_"+tasks[i].id+" onclick=finishTask("+tasks[i].id+")>FINISH</button>";
        var button_hold = createHoldButton(tasks[i]);
        var html = "<tr><td>"+prjName+"</td><td>"+taskName+"</td><td>"+taskPri+"</td><td>"+button_finished+"</td><td>"+
            button_hold+"</td></tr>";
        if (tasks[i].current == true) {
            current_task = html;
            currentTask = tasks[i];
        } else {
            tasksRows.push(html);
        }
    }

    if (current_task == "") {
        var current_task = "<tr><td>No tasks to work on...</td><td>Add more tasks, or release some tasks</td></tr>";
    }

    // No current task selected disable the start task button.
    if (currentTask == null) {
        $("#startTaskBtn").prop('disabled', true);
    } else {
        $("#startTaskBtn").prop('disabled', false);
    }

    var waitingTasks = $("#tasks_table");
    waitingTasks.append(tasksRows.join(""));
    $("#current_task").append(current_task);
}

// Gets a calculation of the remaining time in the pomodoro from the server. And executes some callback function
// that should get the duration as a parameter.
function getRemainingTime(callbackToRun) {
    console.log("getting time.")

    if (currentTask != null) {
        makeRequest('GET',
            "http://localhost:9080/basched/getRemainingPomodoroTime?taskid="+currentTask.id+"&priority="+
                currentTask.priority)
            .then(function (xhr) {
                console.log('got remaining time');
                var duration = JSON.parse(xhr.responseText).duration;
                callbackToRun(duration);
            })
            .catch(logHttpError);
    }
}

function requestUnfinishedTasks() {
    console.log('requestUnfinishedTasks');
    // Get all unfinished tasks.
    makeRequest('GET', "http://localhost:9080/basched/unfinishedtasks")
    .then(function (xhr) {handleTasksReply(xhr);})
    .then(function () {getRemainingTime(displayTime);})
    .catch(logHttpError);
}

function updatePomodoros(taskid, pomodorosToAdd) {
    console.log('updatePomodoros');
    return makeRequest('POST', "http://localhost:9080/basched/updatePomodorosCount?taskid="+taskid+"&pomodorosToAdd="+
        pomodorosToAdd)
    .then(function () {console.log('Pomodoro updated !');})
    .catch(logHttpError);
}

function updateTasksWindow(taskid) {
    console.log('updateTasksWindow');
    return makeRequest('POST', "http://localhost:9080/basched/updateTaskWindowIfNeeded?taskid="+taskid)
    .then(function () {console.log('updateTasksWindow finished!');})
    .catch(logHttpError);
}

function finishTask(id) {
    // Stop the timer if the current task was chosen to finish.
    if (currentTask != null && id == currentTask.id)
        setStartStopButtonState("Stop");

    makeRequest('POST', "http://localhost:9080/basched/finishTask?taskid="+id)
    .then(requestUnfinishedTasks)
    .catch(logHttpError);
}

function makeRequest(method, url) {
    return new Promise(function (resolve, reject) {
        var xhr = new XMLHttpRequest();
        xhr.open(method, url);
        xhr.onload = function() {
            resolve(xhr);
        };
        xhr.onerror = function() {
            reject({
                status: this.status,
                statusText: xhr.statusText
            });
        };
        xhr.send();
    });
}

function logHttpError(err) {
    console.error('An error occurred !', err.statusText);
}