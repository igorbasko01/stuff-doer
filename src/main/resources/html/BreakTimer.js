var BreakTimer = {
    beforeBreak: {
        timer: null,
        waitTime: 5 * 60 * 1000,
        breakTime: 5 * 60 * 1000,
        startButton: null,
        origStartFunc: null,
        globalTimerDisp: null,
        callback: null
    },
    duringBreak: {
        timer: null,
        endTime: null
    },
    enableBreakTimer: function(callback, globalTimerDisp, startButton) {
        this.beforeBreak.timer = setTimeout(this.breakTimerEnded,
                                            this.beforeBreak.waitTime);

        this.beforeBreak.callback = callback;
        
        this.beforeBreak.globalTimerDisp = globalTimerDisp;
        displayTime(this.beforeBreak.breakTime, this.beforeBreak.globalTimerDisp);
        this.beforeBreak.globalTimerDisp.css('color', 'green')
        
        this.beforeBreak.startButton = startButton;
        this.beforeBreak.origStartFunc = startButton.attr("onclick");
        startButton.attr("onclick", "BreakTimer.startBreak()");
        startButton.text("Start Break");
    },
    breakTimerEnded: function() {
        // The scope of this function is different because it is called from
        // inside setInterval, so 'this' doesn't point to BreakTimer.
        clearTimeout(BreakTimer.beforeBreak.timer);
        clearInterval(BreakTimer.duringBreak.timer);
        console.log("Break wait timer ended...");
        BreakTimer.resetStartButton();
        BreakTimer.beforeBreak.globalTimerDisp.css('color', '');
        notifyMe('Break Ended !', 'Time to get back to work !');
        BreakTimer.beforeBreak.callback();
    },
    startBreak: function() {
        console.log("Break started...");
        clearTimeout(BreakTimer.beforeBreak.timer);
        
        this.beforeBreak.startButton.text("Skip Break");
        this.beforeBreak.startButton.attr("onclick", "BreakTimer.skipBreak()");

        var currentTime = new Date().getTime();
        this.duringBreak.endTime = currentTime + this.beforeBreak.breakTime;
        this.duringBreak.timer = setInterval(this.updateTimer, 1000);
    },
    updateTimer: function() {
        var currentTime = new Date().getTime();
        if (currentTime > BreakTimer.duringBreak.endTime) {
            BreakTimer.breakTimerEnded();
        } else {
            displayTime(BreakTimer.duringBreak.endTime - currentTime,
                        BreakTimer.beforeBreak.globalTimerDisp);
        }
    },
    skipBreak: function() {
        console.log("Skipped Break");
        BreakTimer.breakTimerEnded();
    },
    resetStartButton: function() {
        BreakTimer.beforeBreak.startButton.text("Start");
        BreakTimer.beforeBreak.startButton.attr("onclick",
          BreakTimer.beforeBreak.origStartFunc);
    }
}
