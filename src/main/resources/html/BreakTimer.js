var BreakTimer = {
    beforeBreak: {
        timer: null,
        endTime: null,
        waitTime: 0.1 * 60 * 1000, // Change it to 5 minutes.
        breakTime: 5 * 60 * 1000,
        startButton: null,
        origStartFunc: null,
        globalTimerDisp: null,
        callback: null
    },
    enableBreakTimer: function(callback, globalTimerDisp, startButton) {
        var currentTime = new Date().getTime();
        this.beforeBreak.endTime = currentTime + this.beforeBreak.waitTime;
        this.beforeBreak.timer = setInterval(this.breakTimerEnded, 1000);

        this.beforeBreak.callback = callback;
        
        this.beforeBreak.globalTimerDisp = globalTimerDisp;
        displayTime(this.beforeBreak.breakTime, this.beforeBreak.globalTimerDisp);
        this.beforeBreak.globalTimerDisp.css('color', 'green')
        
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
            BreakTimer.beforeBreak.globalTimerDisp.css('color', '')
            BreakTimer.beforeBreak.callback();
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
