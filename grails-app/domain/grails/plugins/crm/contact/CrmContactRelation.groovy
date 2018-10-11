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

/**
 * Relation between two contacts.
 */
class CrmContactRelation {
    boolean primary
    CrmContact a
    CrmContact b
    CrmContactRelationType type
    String description

    static constraints = {
        type(unique: ['a', 'b'])
        description(maxSize: 4000, nullable: true, widget: 'textarea')
    }

    static mapping = {
        primary column: 'is_primary'
    }

    String toString() {
        type.toString()
    }

    CrmContact getRelated(CrmContact origin) {
        if(origin == null) {
            throw new IllegalArgumentException("CrmContactRelation#getRelated() called with null argument")
        }
        if(origin.id == a?.id) {
            return b
        } else if(origin.id == b?.id) {
            return a
        }
        return null
    }

    boolean isPrimaryFor(CrmContact origin) {
        if(a == null || origin == null) {
            return false
        }
        primary == true && origin.id == a.id
    }
}
