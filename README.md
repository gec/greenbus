GreenBus Platform
--------------------

Prerequisites:

- Maven 3
- Postgresql
- Qpid Broker (for integration tests)
- Google Protocol Buffers compiler (version 2.5.0)

### Setting up Postgresql

The Postgres install must be initialized with the databases and users. The relevant configuration is contained in
the init_postgres.sql.

    sudo su postgres -c psql < init_postgres.sql

### Setting up Qpid

Qpid needs have auth settings that match the configuration in `io.greenbus.msg.amqp.cfg`. For development purposes,
the Qpid auth can be disabled entirely by setting `auth=no` in the broker configuration and removing references to the
ACL submodule.

### Building with Maven

Build:

    mvn clean install

### Dependency Projects

The GreenBus platform project is based on [GreenBus Messaging](https://github.com/gec/greenbus-msg).