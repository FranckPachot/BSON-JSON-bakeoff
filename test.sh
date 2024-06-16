[ -f ./target/insertTest-1.0-jar-with-dependencies.jar ] ||
 mvn clean package

# MongoDB
docker run --name db --rm -d -p 27017:27017 mongo
sleep 15
java -jar ./target/insertTest-1.0-jar-with-dependencies.jar $*
docker rm -f db

# PostgreSQL
docker run --name db --rm -d -p 5432:5432 -e POSTGRES_PASSWORD=password postgres
sleep 15
until echo "create database test;" | docker exec -i db psql -U postgres ; do sleep 15 ; done
java -jar ./target/insertTest-1.0-jar-with-dependencies.jar -p $*
docker rm -f db

# YugabyteDB
docker run --name db -d -p 5432:5433 yugabytedb/yugabyte yugabyted start --background=false
sleep 15
until echo "create database test;" | docker exec -i db yugabyted connect ysql ; do sleep 15 ; done
java -jar ./target/insertTest-1.0-jar-with-dependencies.jar -p $*
docker rm -f db
