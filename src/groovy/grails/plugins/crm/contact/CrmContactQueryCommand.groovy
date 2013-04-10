package grails.plugins.crm.contact

/**
 * Command object that receives input from the contact query form.
 */
class CrmContactQueryCommand implements Serializable {
    boolean company
    boolean person
    String number
    String number2
    String ssn
    String duns
    String name
    String parent
    String birthYear
    String birthMonth
    String birthDay
    String title

    String address1
    String address2
    String address3
    String postalCode
    String city
    String region
    String country

    String email
    String telephone
    String url
    String username
    String tags
}
