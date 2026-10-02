URL ?= https://seat-reservation-86o2.onrender.com

.PHONY: burst up down test

# make burst                      -> stampede the live service
# make burst URL=http://localhost:8080 ARGS="-concurrency 500"
burst:
	./burst.sh $(URL) $(ARGS)

up:
	docker compose up --build

down:
	docker compose down

test:
	mvn test
