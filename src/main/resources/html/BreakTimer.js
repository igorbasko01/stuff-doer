var BreakTimer = {
    beforeBreak: {
        timer: null,
        endTime: null,
        waitTime: 0.1 * 60 * 1000, // Change it to 5 minutes.
        startButton: null,
        origStartFunc: null,
        globalTimerDisp: null
    },
    enableBreakTimer: function(globalTimerDisp, startButton) {
        var currentTime = new Date().getTime();
        this.beforeBreak.endTime = currentTime + this.beforeBreak.waitTime;
        this.beforeBreak.timer = setInterval(this.breakTimerEnded, 1000);
        this.beforeBreak.globalTimerDisp = globalTimerDisp;
        this.beforeBreak.startButton = startButton;
        this.beforeBreak.origStartFunc = startButton.attr("onclick");
        startButton.attr("onclick", "BreakTimer.startBreak()")
        startButton.text("Start Break");
    },
    breakTimerEnded: function() {
        var currentTime = new Date().getTime();
        // The scope of this function is different because it is called from
        // inside setInterval, so 'this' doesn't point to BreakTimer.
        if (currentTime > BreakTimer.beforeBreak.endTime) {
            clearInterval(BreakTimer.beforeBreak.timer);
            console.log("Break wait timer ended...");
            BreakTimer.resetStartButton();
        }
    },
    startBreak: function() {
        console.log("Break started...");
        clearInterval(BreakTimer.beforeBreak.timer);
        this.beforeBreak.startButton.text("Skip Break");
    },
    stopBreak: function() {
        
    },
    resetStartButton: function() {
        BreakTimer.beforeBreak.startButton.text("Start");
        BreakTimer.beforeBreak.startButton.attr("onclick",
          BreakTimer.beforeBreak.origStartFunc);
    }
}
