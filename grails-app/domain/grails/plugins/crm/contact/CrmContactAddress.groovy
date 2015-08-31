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

/**
 * A contact can have many addresses, like visiting, postal, delivery, invoice, home, work.
 */
class CrmContactAddress extends CrmAddress {

    CrmAddressType type
    boolean preferred

    static belongsTo = [contact: CrmContact]

    /**
     * Copy all address fields from this instance to a new instance.
     *
     * @return newly created CrmContactAddress instance.
     */
    CrmAddress copy() {
        def addr = super.copy()
        if(addr instanceof CrmContactAddress) {
            addr.type = this.type
            addr.preferred = this.preferred
        }
        return addr
    }

    boolean equals(o) {
        if (this.is(o)) return true
        if (o == null || ! getClass().isAssignableFrom(o.class)) return false

        CrmContactAddress that = (CrmContactAddress) o

        if(id != null) {
            return id == that.id
        }

        return this.contact == that.contact && this.type == that.type && this.toString() == that.toString()
    }

    int hashCode() {
        int result
        if(id != null) {
            result = id.hashCode()
        } else {
            result = (type != null ? type.hashCode() : 0)
            result = 31 * result + (contact != null ? contact.hashCode() : 0)
            def s = this.toString()
            result = 31 * result + (s != null ? s.hashCode() : 0)
        }
        return result
    }
}
