# Grails CRM - Contact Management Plugin

CRM = [Customer Relationship Management](http://en.wikipedia.org/wiki/Customer_relationship_management)

Grails CRM is a set of [Grails Web Application Framework](http://www.grails.org/)
plugins that makes it easy to develop web application with CRM functionality.
With CRM we mean features like:

- Contact Management
- Task/Todo Lists
- Project Management


## Contact Management Plugin
This plugin provides domain classes and services for managing companies,
individuals or any other type of contacts, and relationships between them.

### Domain Classes
The plugin provides a simple domain model for contacts.

**CrmContact** - All contact type are stored here, companies, individuals, organisations, etc.

**CrmContactAddress** - One contact can have many addresses, for example postal, visit, delivery, etc.

**CrmAddressType** - Lookup table that defines the type of a CrmContactAddress instance (postal, visit, delivery, etc.)

**CrmContactRelation** - Link table between two contacts

**CrmContactRelationType** - Lookup table that defines a relation type, for example "emplyee", "chairman", "family", etc.

### Services

**CrmContactService** Main service for creating and managing contact instances

## Related plugins

There is a sub-plugin called *crm-contact-lite* that provides user interface (twitter bootstrap) for
managing contacts.