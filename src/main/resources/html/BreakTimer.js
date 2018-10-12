var BreakTimer = {
    beforeBreak: {
        timer: null,
        endTime: null,
        waitTime: 0.5 * 60 * 1000,
        startButton: null
    },
    enableBreakTimer: function(globalTimerDisp, startButton) {
        var currentTime = new Date().getTime();
        this.beforeBreak.endTime = currentTime + this.beforeBreak.waitTime;
        this.beforeBreak.timer = setInterval(this.breakTimerEnded, 1000);
        this.beforeBreak.startButton = startButton;
        startButton.text("Start Break");
    },
    breakTimerEnded: function() {
        var currentTime = new Date().getTime();
        // The scope of this function is different because it is called from
        // inside setInterval, so 'this' doesn't point to BreakTimer.
        if (currentTime > BreakTimer.beforeBreak.endTime) {
            clearInterval(BreakTimer.beforeBreak.timer);
            console.log("Break wait timer ended...");
            BreakTimer.beforeBreak.startButton.text("Start");
        }
    }
}
