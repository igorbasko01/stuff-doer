var timer;
var timeEnd;

// request permission on page load
document.addEventListener('DOMContentLoaded', function () {
  if (Notification.permission !== "granted")
    Notification.requestPermission();
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