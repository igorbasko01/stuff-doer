// Get all the available projects.
document.addEventListener('DOMContentLoaded', function () {
    var xhttp = new XMLHttpRequest();
    xhttp.onreadystatechange = function() {
        if (this.readyState == 4 && this.status == 200) {
            console.log(this.responseText);
        }
    };

    xhttp.open("GET", "http://localhost:9080/basched/allprojects", true);
    xhttp.send();
});

function addTask() {
    console.log("Hello !");
}