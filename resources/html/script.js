var timer;
var timeEnd;

var priority = ["Immediate", "High", "Regular"];

// request permission on page load
document.addEventListener('DOMContentLoaded', function () {
  if (Notification.permission !== "granted")
    Notification.requestPermission();

  // Get all unfinished tasks.
  var xhttp = new XMLHttpRequest();
  xhttp.onreadystatechange = function() {
    if (this.readyState == 4 && this.status == 200) {
        handleTasksReply(this.responseText);
    }
  };

  displayTime(getRemainingTime());

  xhttp.open("GET", "http://localhost:9080/basched/unfinishedtasks", true);
  xhttp.send();
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

    notification.onclick = function () {
        window.location.href = 'http://localhost:9080/html/index.html';
//      window.open("http://localhost:9080/html/index.html");
    };
  }

}

function startTimer() {
    timer = setInterval(timerEnds, 1000);
    timeEnd = new Date().getTime() + getRemainingTime();
}

// Checks if the timer ended. If ended notifies the user and stops the interval.
function timerEnds() {
    var currentTime = new Date().getTime();
    if (currentTime > timeEnd) {
        notifyMe();
        clearInterval(timer);
        timeEnd = currentTime;
    }

    displayTime(timeEnd - currentTime);
}

function displayTime(timeToDisplay) {
    var minutesRemaining = Math.floor(timeToDisplay / 1000 / 60);
    var secondsRemaining = Math.floor((timeToDisplay / 1000) - (minutesRemaining * 60));

    var mintsToDisp = (minutesRemaining < 10) ? "0" + minutesRemaining : minutesRemaining;
    var scndsToDisp = (secondsRemaining < 10) ? "0" + secondsRemaining : secondsRemaining;

    $("#time").text(mintsToDisp + ":" + scndsToDisp);
}

function gotoAddTaskPage() {
    window.location.href = 'http://localhost:9080/html/AddTask.html';
}

function handleTasksReply(response) {
    var tasks = JSON.parse(response).tasks;
    var tasksRows = [];
    var currentTask = ""
    for (var i = 0; i < tasks.length; i++) {
        var taskName = tasks[i].name;
        var taskPri = priority[tasks[i].priority];
        var html = "<tr><td>"+taskName+"</td><td>"+taskPri+"</td></tr>"
        if (tasks[i].current == true) {
            currentTask = html;
        } else {
            tasksRows.push(html);
        }
    }

    var waitingTasks = $("#tasks_table");
    waitingTasks.append(tasksRows.join(""));
    $("#current_task").append(currentTask);
}

// Returns the amount of time in ms remaining in the pomodoro of the current task.
//TODO: Extract the remaining time from the task itself.
function getRemainingTime() {
    return 25 * 60 * 1000;
}