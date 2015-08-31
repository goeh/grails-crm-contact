/*
 * Copyright (c) 2015 Goran Ehrsson.
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

import spock.lang.Specification

/**
 * Test spec for CrmContactAddress.
 */
class CrmContactAddressSpec extends Specification {

    def "test equals(null)"() {
        expect:
        new CrmContactAddress(city: "London").equals(null) == false
    }

    def "test equals(SomeOtherType)"() {
        expect:
        new CrmContactAddress(city: "Paris").equals(new Date()) == false
    }

    def "copy function"() {
        given:
        def type = new CrmAddressType(name: "Postal address", param: "postal")
        def a1 = new CrmContactAddress(type: type, address1: "Regent Street", postalCode: "12345", city: "London")

        when:
        def a2 = a1.copy()

        then:
        a1 == a2
        a1.address1 == a2.address1
        a1.address2 == a2.address2
        a1.address3 == a2.address3
        a1.postalCode == a2.postalCode
        a1.city == a2.city
    }
}
