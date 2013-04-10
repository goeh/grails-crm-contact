/*
 * Copyright (c) 2013 Goran Ehrsson.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugins.crm.contact

import grails.plugins.crm.core.SearchUtils
import grails.plugins.crm.core.TenantEntity
import grails.plugins.crm.core.AuditEntity
import grails.plugins.sequence.SequenceEntity
import java.text.SimpleDateFormat

@TenantEntity
@AuditEntity
@SequenceEntity(property = "number", maxSize = 20, blank = false, unique = "tenantId")
class CrmContact {

    private static final int DUNS_NUMBER_LENGTH = 9

    String firstName // Given name(s) for person, null for company.
    String lastName  // Family name for person, null for company.
    String name      // firstName + lastName (set in beforeValidate) for individuals and complete name for company
    String namePhonetic // Contact name 'metaphone' encoded
    String number2 // Extra customer number
    String ssn // Social Security Number (personnr/orgnr in Sweden)
    String duns // D&B D-U-N-S(tm) identification number.
    String description
    String picture
    String gender
    Integer birthYear
    Integer birthMonth
    Integer birthDay
    String title
    CrmContact parent // TODO Remove and use CrmContactRelation instead
    String email
    String telephone
    String mobile
    String fax
    String url
    String guid // this guid typically references the user that matches this contact.
    String username

    static hasMany = [addresses: CrmContactAddress, children: CrmContact]
    static constraints = {
        firstName(maxSize: 64, nullable: true, validator: { val, obj ->
            (val || obj.lastName || obj.name) ? true : 'blank'
        })
        lastName(maxSize: 64, nullable: true, validator: { val, obj ->
            (val || obj.firstName || obj.name) ? true : 'blank'
        })
        name(maxSize: 80, nullable: true, validator: { val, obj ->
            (val || obj.firstName || obj.lastName) ? true : 'blank'
        })
        namePhonetic(maxSize: 16, nullable: true)
        number2(maxSize: 20, nullable: true)
        ssn(maxSize: 20, nullable: true)
        duns(maxSize: 20, nullable: true, validator: { val, obj ->
            if (val && obj.domainClass.grailsApplication.config.crm.contact.duns.strict && val.length() > DUNS_NUMBER_LENGTH) {
                return 'maxSize'
            }
            return null
        })
        description(maxSize: 2000, nullable: true, widget: 'textarea')
        picture(maxSize: 255, nullable: true)
        gender(maxSize: 1, inList: ['F', 'M'], nullable: true)
        birthYear(min: 1500, max: 2099, nullable: true)
        birthMonth(min: 1, max: 12, nullable: true)
        birthDay(min: 1, max: 31, nullable: true)
        title(maxSize: 80, nullable: true)
        parent(nullable: true, validator: { val, obj ->
            val == obj ? 'circularReference' : null
        })

        email(maxSize: 80, nullable: true, email: true)
        telephone(maxSize: 32, nullable: true)
        mobile(maxSize: 32, nullable: true)
        fax(maxSize: 32, nullable: true)
        url(maxSize: 255, nullable: true)

        guid(maxSize: 36, nullable: true)
        username(size: 3..80, maxSize: 80, nullable: true)
    }

    static mapping = {
        sort 'name': 'asc'
        name index: 'crm_contact_name_idx'
        guid index: 'crm_contact_guid_idx'
        children sort: 'name'
        //addresses cascade: 'all-delete-orphan'
    }

    static transients = ['preferredPhone', 'address', 'myAddress', 'company', 'person', 'vcard', 'dao', 'relations']

    static searchable = {
        name boost: 1.5
    }
    static taggable = true
    static attachmentable = true
    static dynamicProperties = true
    static relatable = true
    static auditable = true

    def beforeValidate() {
        if (!number) {
            number = getNextSequenceNumber()
        }
        if (!guid) {
            guid = UUID.randomUUID().toString()
        }
        if (firstName && lastName) {
            name = firstName + ' ' + lastName
        } else if (firstName) {
            name = firstName
        } else if (lastName) {
            name = lastName
        }
        namePhonetic = name ? SearchUtils.doubleMetaphoneEncode(name) : null

        if (duns && domainClass.grailsApplication.config.crm.contact.duns.strict) {
            duns = duns.replaceAll(/\D/, '').trim() ?: null
        }
    }

    transient List<CrmContactRelation> getRelations() {
        CrmContactRelation.createCriteria().list() {
            or {
                eq('a', this)
                eq('b', this)
            }
            type {
                order 'orderIndex', 'asc'
            }
            a {
                order 'name', 'asc'
            }
            b {
                order 'name', 'asc'
            }
        }
    }

    transient String getPreferredPhone() {
        if (mobile) {
            return mobile
        }
        if (telephone) {
            return telephone
        }
        if (parent?.telephone) {
            return parent.telephone
        }
        return null
    }

    transient CrmContactAddress getAddress() {
        getMyAddress() ?: parent?.address
    }

    transient CrmContactAddress getMyAddress() {
        (addresses?.find { it.preferred } ?: addresses?.find { it })
    }

    def setAddress(addressBean) {
        def addr = getMyAddress()
        if (!addr) {
            addr = new CrmContactAddress(preferred: true)
            addToAddresses(addr)
        }
        def props = ['address1', 'address2', 'address3', 'postalCode', 'city', 'region', 'country', 'timezone', 'latitude', 'longitude']
        for (p in props) {
            addr[p] = addressBean[p]
        }
        addr.preferred = true
        return addr
    }

    transient boolean isCompany() {
        !(firstName || lastName)
    }

    transient boolean isPerson() {
        (firstName || lastName)
    }

    String toString() {
        name
    }

    transient String getVcard() {
        def postalAddress = new StringBuilder()
        if (address?.address1) {
            postalAddress << address.address1
        }
        if (address?.address2) {
            if (postalAddress.length() > 0) {
                postalAddress << ' '
            }
            postalAddress << address.address2
        }
        if (address?.address3) {
            if (postalAddress.length() > 0) {
                postalAddress << ' '
            }
            postalAddress << address.address3
        }
        def s = new StringBuilder()
        s << "BEGIN:VCARD\n"
        s << "VERSION:3.0\n"
        s << "N:${lastName ?: ''};${firstName ?: ''};;;\n"
        s << """FN: ${name ?: ''}\n"""
        s << "ORG:${company ? name : parent?.name}\n"
        s << "TITLE:${title ? title.replace(',', '\\,') : ''}\n"
        //s << "PHOTO:http://www.example.com/dir_photos/my_photo.gif\n"
        s << "TEL;TYPE=work,voice,pref:${telephone ?: ''}\n"
        s << "TEL;TYPE=cell,voice:${mobile ?: ''}\n"
        s << "EMAIL;type=internet,pref:${email ?: ''}\n"
        s << "ADR;TYPE=work,postal,pref:;;${postalAddress};${address?.city ?: ''};${address?.region ?: ''};${address?.postalCode ?: ''};${address?.country ?: ''}\n"

        def timestampFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        s << "REV:${timestampFormat.format(lastUpdated ?: dateCreated)}\n"

        s << "END:VCARD\n"

        s.toString()
    }

    private static final List DAO_PROPS = ['name', 'firstName', 'lastName', 'title', 'telephone', 'mobile', 'fax', 'email', 'url',
            'guid', 'username', 'number', 'number2', 'ssn', 'duns', 'description', 'gender', 'birthYear', 'birthMonth', 'birthDay']

    private Map getSelfProperties(List<String> props) {
        props.inject([:]) { m, i ->
            def v = this."$i"
            if (v != null) {
                m[i] = v
            }
            m
        }
    }

    transient Map<String, Object> getDao(boolean includeChildren = true) {
        def map = getSelfProperties(DAO_PROPS)
        map.address = address?.getDao() ?: [:]
        if (parent) {
            map.parent = parent.getDao(false)
        }
        if (includeChildren && children) {
            map.children = children.collect { it.getSelfProperties(DAO_PROPS) }
        }
        return map
    }

    public static final List BIND_WHITELIST = DAO_PROPS.asImmutable()
}
