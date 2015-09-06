/*
 * Copyright (c) 2014 Goran Ehrsson.
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

import grails.plugins.crm.core.CrmAddress
import grails.plugins.crm.core.CrmAddressInformation
import grails.plugins.crm.core.TenantEntity
import grails.plugins.crm.core.AuditEntity
import grails.plugins.crm.core.CrmContactInformation
import grails.plugins.sequence.SequenceEntity
import org.apache.commons.codec.language.DoubleMetaphone
import org.apache.commons.codec.language.Soundex

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.regex.Pattern

@TenantEntity
@AuditEntity
@SequenceEntity(property = "number", maxSize = 20, blank = false, unique = "tenantId")
class CrmContact implements CrmContactInformation {

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

    static hasMany = [addresses: CrmContactAddress, children: CrmContact, categories: CrmContactCategory]

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

    static mappedBy = [children: 'parent']

    static transients = ['address']

    static searchable = {
        name boost: 1.5
    }
    static taggable = true
    static attachmentable = true
    static dynamicProperties = true
    static relatable = true
    static auditable = true

    static final Comparator<CrmContact> lastNameFirstNameComparator =
            { a, b -> a.person ? (a.lastName == b.lastName ? a.firstName <=> b.firstName : a.lastName <=> b.lastName) : a.name <=> b.name } as Comparator

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
        namePhonetic = name ? CrmContact.doubleMetaphoneEncode(name) : null

        if (duns && domainClass.grailsApplication.config.crm.contact.duns.strict) {
            duns = duns.replaceAll(/\D/, '').trim() ?: null
        }
    }

    transient List<CrmContactRelation> getRelations(String relationType = null) {
        if (!ident()) {
            return null
        }
        CrmContactRelation.createCriteria().list() {
            or {
                eq('a', this)
                eq('b', this)
            }
            type {
                if (relationType != null) {
                    eq('param', relationType)
                }
                order 'orderIndex', 'asc'
            }
            a {
                order 'name', 'asc'
            }
            b {
                order 'name', 'asc'
            }
            cache true
        }
    }

    transient Map getPrimaryRelation() {
        if (!ident()) {
            return null
        }
        def rel = CrmContactRelation.createCriteria().get() {
            eq('a', this)
            eq('primary', true)
            cache true
        }
        if (rel) {
            def other = rel.b
            def result = other.dao
            result.id = other.id
            result.relation = rel.type.dao
            return result
        }
        return null
    }

    transient CrmContact getPrimaryContact() {
        if (!ident()) {
            return null
        }
        CrmContactRelation.createCriteria().get() {
            eq('a', this)
            eq('primary', true)
            cache true
        }?.b
    }

    transient String getPreferredPhone() {
        if (mobile) {
            return mobile
        }
        if (telephone) {
            return telephone
        }
        primaryContact?.telephone
    }

    transient CrmContactAddress getAddress(String type = null) {
        CrmContactAddress a
        if (type != null) {
            a = addresses?.find { it.type.param == type }
            if (!a) {
                a = primaryContact?.getAddress(type)
            }
        } else {
            a = getMyAddress() ?: primaryContact?.address
        }
        return a
    }

    transient CrmContactAddress getMyAddress() {
        (addresses?.find { it.preferred } ?: addresses?.find { it })
    }

    transient CrmContactAddress getPrimaryContactAddress(CrmAddressType addressType = null) {
        primaryContact?.addresses?.find { addressType == null || it.type == addressType }
    }

    CrmAddress setAddress(addressBean) {
        CrmContactAddress addr = getMyAddress()
        if (!addr) {
            addr = new CrmContactAddress(preferred: true)
            addToAddresses(addr)
        }
        final List<String> props = ['address1', 'address2', 'address3', 'postalCode', 'city', 'region', 'country', 'timezone', 'latitude', 'longitude']
        for (p in props) {
            addr[p] = addressBean[p]
        }
        addr.preferred = true
        return addr
    }

    /**
     * Return the complete address, usually with comma between street name, postal code, etc.
     *
     * @deprecated use getAddressInformation().toString() instead
     * @return
     */
    transient String getFullAddress() {
        def a = getAddressInformation()
        return a != null ? a.toString() : ''
    }

    @Override
    transient CrmAddressInformation getAddressInformation() {
        getAddress()
    }

    @Override
    transient String getCompanyName() {
        primaryContact?.name
    }

    @Override
    transient Long getCompanyId() {
        primaryContact?.id
    }

    @Override
    transient String getFullName() {
        final StringBuilder s = new StringBuilder()
        s << name
        def p = getPrimaryContact()
        if (p) {
            s << ", "
            s << p.name
        }
        s.toString()
    }

    transient boolean isCompany() {
        !(firstName || lastName)
    }

    transient boolean isPerson() {
        (firstName || lastName)
    }

    @Override
    String toString() {
        if(name) {
            return name
        } else if (firstName && lastName) {
            return firstName + ' ' + lastName
        } else if (firstName) {
            return firstName
        } else if (lastName) {
            return lastName
        }
        return 'null'
    }

    transient String getVcard() {
        final CrmAddress a = this.getAddress(null)
        final StringBuilder postalAddress = new StringBuilder()
        if (a?.address1) {
            postalAddress << a.address1
        }
        if (a?.address2) {
            if (postalAddress.length() > 0) {
                postalAddress << ' '
            }
            postalAddress << a.address2
        }
        if (a?.address3) {
            if (postalAddress.length() > 0) {
                postalAddress << ' '
            }
            postalAddress << a.address3
        }
        final StringBuilder s = new StringBuilder()
        s << "BEGIN:VCARD\n"
        s << "VERSION:3.0\n"
        s << "N:${lastName ?: ''};${firstName ?: ''};;;\n"
        s << """FN:${name ?: ''}\n"""
        s << "ORG:${isCompany() ? name : primaryContact?.name}\n"
        s << "TITLE:${title ? title.replace(',', '\\,') : ''}\n"
        //s << "PHOTO:http://www.example.com/dir_photos/my_photo.gif\n"
        s << "TEL;TYPE=work,voice,pref:${telephone ?: ''}\n"
        s << "TEL;TYPE=cell,voice:${mobile ?: ''}\n"
        s << "EMAIL;type=internet,pref:${email ?: ''}\n"
        s << "ADR;TYPE=work,postal,pref:;;${postalAddress};${a?.city ?: ''};${a?.region ?: ''};${a?.postalCode ?: ''};${a?.country ?: ''}\n"

        final DateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        s << "REV:${timestampFormat.format(lastUpdated ?: dateCreated)}\n"

        s << "END:VCARD\n"

        s.toString()
    }

    public static
    final List<String> BIND_WHITELIST = ['name', 'firstName', 'lastName', 'title', 'telephone', 'mobile', 'fax', 'email', 'url',
                                         'guid', 'username', 'number', 'number2', 'ssn', 'duns', 'description', 'gender', 'birthYear', 'birthMonth', 'birthDay'].asImmutable()

    private Map<String, Object> getSelfProperties(List<String> props) {
        props.inject([:]) { m, i ->
            def v = this."$i"
            if (v != null) {
                m[i] = v
            }
            m
        }
    }

    transient Map<String, Object> getDao(boolean includeChildren = true) {
        final Map<String, Object> map = getSelfProperties(BIND_WHITELIST)
        map.tenant = tenantId
        map.address = address?.getDao() ?: [:]
        if (parent) {
            map.parent = parent.getDao(false)
        }
        if (includeChildren && children) {
            map.children = children.collect { it.getSelfProperties(BIND_WHITELIST) }
        }
        return map
    }

    static String soundexEncode(final String s) {
        if (!s) {
            return null
        }
        String soundex
        try {
            soundex = new Soundex().encode(getNormalizedName(s))
        } catch (IllegalArgumentException e) {
            System.err.println("WARNING: Failed to soundex encode '${s}'")
            soundex = null
        }
        return soundex
    }

    static String doubleMetaphoneEncode(final String s) {
        if (!s) {
            return null
        }
        String result
        try {
            result = new DoubleMetaphone().encode(getNormalizedName(s))
        } catch (IllegalArgumentException e) {
            System.err.println("WARNING: Failed to double metaphone encode '${s}'")
            result = null
        }
        return result
    }

    private static final Pattern NORMALIZE_REGEX1 = Pattern.compile(/^(ab|hb|kb|aktiebolaget)\s+/)
    private static final Pattern NORMALIZE_REGEX2 = Pattern.compile(/(ab|hb|kb|aktiebolag|oy|ltd|inc|llc)\.?$/)

    /**
     * Swedish hack! Remove company name prefix and suffix.
     */
    static String getNormalizedName(final String name) {
        name.toLowerCase().replaceAll(NORMALIZE_REGEX1, '').replaceAll(NORMALIZE_REGEX2, '').trim()
    }
}
