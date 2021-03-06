var timer;
var timeEnd;
var globalTimeEnd;

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

function notifyMe(msgTitle, msgBody) {
    var audio = new Audio("resources/mp3/Alarm.mp3");
    audio.play();
    
  if (!Notification) {
    alert('Desktop notifications not available in your browser. Try Chromium.');
    return;
  }

  if (Notification.permission !== "granted")
    Notification.requestPermission();
  else {
    var notification = new Notification(msgTitle, {
//      icon: 'http://cdn.sstatic.net/stackexchange/img/logos/so/so-icon.png',
      body: msgBody,
    });


    notification.onclick = function () {
        window.location.href = baseURL;
//      window.open("http://localhost:9080/html/index.html");
    };
  }

}

function toggleStartStopButton() {
    console.log("toggleStartStopButton");
    var btnStart = $("#startTaskBtn");
    var btnState = btnStart.text();
    var promise = Promise.resolve();
    if (btnState == "Start") {
        startTimer();
        btnStart.text("Stop");
    } else {
        promise = stopTimer();
        btnStart.text("Start");
    }

    return promise;
}

function setStartStopButtonState(newState) {
    var btnStart = $("#startTaskBtn");
    var btnState = btnStart.text();
    var promise = Promise.resolve();
    // If the states are equal it means that currently the button displays the state that we want to change to.
    // For example, currently the text of the button is "Stop" because the task is running, and we want to go to
    // "Stop" state, so it has the same text.
    if (btnState == newState)
        promise = toggleStartStopButton();

    return promise;
}

function startTimer() {
    var taskDuration;
    
    startTaskRequest();
    getRemainingTime(remainingTimeScope.TASK)
    .then(function (taskDur) {taskDuration = taskDur;
                              return getRemainingTime(remainingTimeScope.GLOBAL);})
    .then(function (globDur) {resetIntervals(taskDuration, globDur);});
}

function resetIntervals(taskDuration, globDuration) {
    var currentTime = new Date().getTime();
    timeEnd = currentTime + taskDuration;
    globalTimeEnd = currentTime + globDuration;

    timer = setInterval(timerEnds, 1000);

    resetCommitInterval(currentTime);
}

function stopTimer() {
    clearInterval(timer);
    return stopTaskRequest();
}

// Sets when the commit interval should happen.
function resetCommitInterval(currentTime) {
    intervalEnd = currentTime + intervalToUpdate_ms;
}

// Checks if the timer ended. If ended notifies the user and stops the interval.
function timerEnds() {
    var currentTime = new Date().getTime();
    var promise;
    if (currentTime > timeEnd || currentTime > globalTimeEnd) {
        promise = setStartStopButtonState("Stop");
    }
    if (currentTime > globalTimeEnd) {
        notifyMe('Timer Ended !', 'Well Done, Time for a break.');
        startBreakTimer($("#global_time"), $("#startTaskBtn"));
        globalTimeEnd = currentTime;
    }
    else if (currentTime > timeEnd) {
        notifyMe('Timer Ended !', 'Well Done, Task finished.');
        promise
        .then(function () { return updatePomodoros(currentTask.id, 1); })
        .then(function () { return updateTasksWindow(currentTask.id); })
        .then(function () { requestUnfinishedTasks(); });
        timeEnd = currentTime;
    } else {
        // If the timer ends, avoid duplicate record commit.
        handleCommitInterval(currentTime);
        displayTime(timeEnd - currentTime, $("#time"));
        displayTime(globalTimeEnd - currentTime, $("#global_time"))
    }
}

// Checks if an interval passed and commits the work to the server.
function handleCommitInterval(currentTime) {
    if (currentTime > intervalEnd) {
        pingTask();
        resetCommitInterval(currentTime);
    }

    displayTime(intervalEnd - currentTime, $("#intervalTime"));
}

function pingTask() {
    console.log("Ping !");
    makeRequest('POST', baseURL + "basched/pingTask?taskid="+currentTask.id)
    .catch(logHttpError);
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
    window.location.href = baseURL + 'html/AddTask.html';
}

function createHoldButton(task) {
    var buttonText = (task.status == 0 || task.status == 1) ? "HOLD" : "RELEASE";
    return "<button id=hold_"+task.id+" onclick=toggleHold("+task.id+")>"+buttonText+"</button>";
}

function toggleHold(id) {
    // Stop the timer if the current task was chosen to hold.
    if (currentTask != null && id == currentTask.id)
        setStartStopButtonState("Stop");

    makeRequest('POST', baseURL + "basched/toggleHold?taskid="+id)
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
        var button_pri = createPriorityChangeButtons(tasks[i]);
        var html = "<tr><td>"+prjName+"</td><td>"+taskName+"</td><td>"+taskPri+"</td><td>"+button_finished+"</td><td>"+
            button_hold+"</td>"+button_pri+"</tr>";
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

function createPriorityChangeButtons(task) {
    var btnUp = "<td><button id=pri_up_"+task.id+" onclick=changeTaskPriority("+task.id+","+(task.priority-1)+")>+</button></td>";
    var btnDown = "<td><button id=pri_down_"+task.id+" onclick=changeTaskPriority("+task.id+","+(task.priority+1)+")>-</button></td>";
    var finalButtons = "";
    if (task.priority > 0)
        finalButtons += btnUp;
    if (task.priority < 2)
        finalButtons += btnDown;

    return finalButtons;
}

function changeTaskPriority(taskid, newPriority) {
    // Stop the currently running task.
    if (currentTask != null && taskid == currentTask.id)
        setStartStopButtonState("Stop");

    makeRequest('POST', baseURL + "basched/updatePriority?taskid="+taskid+"&priority="+newPriority)
    .then(function () { requestUnfinishedTasks(); })
    .catch(logHttpError);
}

// Gets a calculation of the remaining time in the pomodoro from the server. And executes some callback function
// that should get the duration as a parameter.
function getRemainingTime(scope) {
    console.log("getting time. Scope: " + scope)

    var servicePath = "404.html"
    if (scope == remainingTimeScope.TASK) {
        servicePath = "basched/getRemainingPomodoroTime?taskid="+currentTask.id
    }
    else {
        servicePath = "basched/getRemainingGlobalPomodoroTime"
    }

    if (currentTask != null) {
        return makeRequest('GET',
            baseURL + servicePath)
            .then(function (xhr) {
                console.log('got remaining time');
                return JSON.parse(xhr.responseText).duration;
            })
    }
}

function requestUnfinishedTasks() {
    console.log('requestUnfinishedTasks');
    // Get all unfinished tasks.
    makeRequest('GET', baseURL + "basched/unfinishedtasks")
    .then(function (xhr) {handleTasksReply(xhr);})
    .then(function () {return getRemainingTime(remainingTimeScope.TASK);})
    .then(function (taskDur) {displayTime(taskDur, $("#time"));})
    .then(function () {return getRemainingTime(remainingTimeScope.GLOBAL);})
    .then(function (glbDur) {displayTime(glbDur, $("#global_time"));})
    .catch(logHttpError);
}

function updatePomodoros(taskid, pomodorosToAdd) {
    console.log('updatePomodoros');
    return makeRequest('POST', baseURL + "basched/updatePomodorosCount?taskid="+taskid+"&pomodorosToAdd="+
        pomodorosToAdd)
    .then(function () {console.log('Pomodoro updated !');})
    .catch(logHttpError);
}

function updateTasksWindow(taskid) {
    console.log('updateTasksWindow');
    return makeRequest('POST', baseURL + "basched/updateTaskWindowIfNeeded?taskid="+taskid)
    .then(function () {console.log('updateTasksWindow finished!');})
    .catch(logHttpError);
}

function finishTask(id) {
    // Stop the timer if the current task was chosen to finish.
    if (currentTask != null && id == currentTask.id)
        setStartStopButtonState("Stop");

    makeRequest('POST', baseURL + "basched/finishTask?taskid="+id)
    .then(requestUnfinishedTasks)
    .catch(logHttpError);
}

function startTaskRequest() {
    makeRequest('POST', baseURL + "basched/startTask?taskid="+currentTask.id+"&priority="+currentTask.priority)
    .catch(logHttpError);
}

function stopTaskRequest() {
    return makeRequest('POST', baseURL + "basched/stopTask?taskid="+currentTask.id)
    .catch(logHttpError);
}

function startBreakTimer(globalTimerDisp, startButton) {
    console.log("Starting break timer...");
    BreakTimer.enableBreakTimer(requestUnfinishedTasks, globalTimerDisp, startButton)
}
