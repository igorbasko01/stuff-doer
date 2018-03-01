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
    timeEnd = new Date().getTime() + (5 * 1000);
}

function timerEnds() {
    var currentTime = new Date().getTime();
    if (currentTime > timeEnd) {
        notifyMe();
        clearInterval(timer);
    }

    document.getElementById("time").innerHTML = (timeEnd - currentTime) / 1000;
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