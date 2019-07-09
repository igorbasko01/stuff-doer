.PHONY: clean assemble run tail extract-logs

clean:
	docker stop -t 10 stuff-doer

assemble:
	sbt assembly

run: clean assemble
	docker rm stuff-doer
	docker build -t stuff-doer .
	docker run -p 80:9080 --name stuff-doer --mount src=stuff-prod,target=/root stuff-doer

tail:
	docker exec -i -t stuff-doer sh -c "tail -n 100 -f logs/stuff-doer*.log"

extract-logs:
	docker cp stuff-doer:/usr/local/stuff-doer/logs/ .
