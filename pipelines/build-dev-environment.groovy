node {

    def database_engine_options = [
        'MYSQL',
        'POSTGRES',
        'ORACLEXE'
    ]

    properties([
        parameters([
            string(
                name: 'ENVIRONMENT_NAME', 
                trim: true,
            ),
            password(
                name: 'DB_PASSWORD', 
                description: 'Password to use for MySQL container - root user', 
                defaultValue: 'defaultPassword'
            ),
            string(
                name: 'DB_PORT',
                description: 'Database port to be exposed 3307 to 3310 (default 3307).', 
                trim: true, 
                defaultValue: '3307'
            ),
            booleanParam(
                name: 'SKIP_STEP_1', 
                defaultValue: false, 
                description: 'STEP 1 - RE-CREATE DOCKER IMAGE'
            ),
            choice(
                name: 'DATABASE_ENGINE', 
                choices: database_engine_options, 
                description: 'Please Select One'
            )
        ])
    ])


    stage ('Validate parameters') {
        int port_num = Integer.parseInt(params.DB_PORT)
        
        if (port_num < 3307 || port_num > 3310) {
            error('Selected port number out of range: ' + port_num)
        }
    }

    stage('Checkout GIT repository') {
        git(
            branch: 'main',
            credentialsId: 'gh_integration_mmarcal',
            url: 'ssh://git@github.com:m-marcal/oracle-challenge.git'
        )
    }

    stage('Create latest Docker image') {
        
        if (params.SKIP_STEP_1) {
            echo "Skipping STEP1"

            // Exit stage
            return 
        }

        switch(DATABASE_ENGINE) {

            case 'MYSQL':
                sh """
                    sed 's/<PASSWORD>/$DB_PASSWORD/g' pipelines/include/create_developer_mysql.template > pipelines/include/create_developer.sql
                """

                sh """
                    docker build -t $params.ENVIRONMENT_NAME:latest -f pipelines/Dockerfile_mysql pipelines/
                """
                break

            case 'POSTGRES':
                sh """
                    sed 's/<PASSWORD>/$DB_PASSWORD/g' pipelines/include/create_developer_postgres.template > pipelines/include/create_developer.sql
                """

                sh """
                    docker build -t $params.ENVIRONMENT_NAME:latest -f pipelines/Dockerfile_postgres pipelines/
                """
                break

            case 'ORACLEXE':
                sh """
                    sed 's/<PASSWORD>/$DB_PASSWORD/g' pipelines/include/create_developer_oraclexe.template > pipelines/include/create_developer.sql
                """

                sh """
                    docker build -t $params.ENVIRONMENT_NAME:latest -f pipelines/Dockerfile_oraclexe pipelines/
                """
                break

        }
    }

    stage('Start new container using latest image and create user') {

        def dateTime = sh(script: "date +%Y%m%d%H%M%S", returnStdout: true).trim()
        def containerName = "${params.ENVIRONMENT_NAME}_${dateTime}"

        switch(DATABASE_ENGINE) {

            case 'MYSQL':
                sh """
                  docker run -itd --name ${containerName} --rm -e MYSQL_ROOT_PASSWORD='$DB_PASSWORD' -p $params.DB_PORT:3306 $params.ENVIRONMENT_NAME:latest
                """
                break

            case 'POSTGRES':
                sh """
                  docker run -itd --name ${containerName} --rm -e POSTGRES_PASSWORD='$DB_PASSWORD' -p $params.DB_PORT:5432 $params.ENVIRONMENT_NAME:latest
                """
                break

            case 'ORACLEXE':
                sh """
                  docker run -itd --name ${containerName} --rm -e ORACLE_PWD='$DB_PASSWORD' -p $params.DB_PORT:1521 $params.ENVIRONMENT_NAME:latest
                """
                break
        }

        sleep 10 // Wait for container to warmup

        def load_sql_script_path = "/scripts/create_developer.sql"
        def data_dump_label = "Inserting data."
        def max_retries = 3
        while (max_retries > 0) {
            
            try {

                switch(DATABASE_ENGINE) {

                    case 'MYSQL':
                        sh(
                            label: data_dump_label,
                            script: """ docker exec ${containerName} /bin/bash -c 'mysql --user="root" --password="$DB_PASSWORD" < $load_sql_script_path' """
                        )
                        break

                    case 'POSTGRES':
                        sh(
                            label: data_dump_label,
                            script: """ docker exec ${containerName} /bin/bash -c 'psql -Upostgres -f $load_sql_script_path' """
                        )
                        break

                    case 'ORACLEXE':
                        sh(
                            label: data_dump_label,
                            script: """ docker exec ${containerName} /bin/bash -c 'sqlplus sys/$DB_PASSWORD as sysdba @$load_sql_script_path' """
                        )
                        break
                }

                break; // get out of while loop

            } catch (Exception e) {
                sleep 5
                echo "Will try again in 5s"
            }

            max_retries--
        }

        echo """ Docker container created: ${containerName} """
    }
}