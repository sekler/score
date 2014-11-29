/*******************************************************************************
 * (c) Copyright 2014 Hewlett-Packard Development Company, L.P.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License is available at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *******************************************************************************/
package org.eclipse.score.engine.queue.entities;

import org.apache.commons.lang.Validate;

import java.util.Collections;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User:
 * Date: 07/08/13
 */
public class ExecStateIdList {

    private List<Long> list = Collections.emptyList();

    	@SuppressWarnings("unused")
    	private ExecStateIdList(){/*used by JSON*/}

    	public ExecStateIdList(List<Long> list){
    		Validate.notNull(list, "A list is null");
    		this.list = list;
    	}

    	public List<Long> getList() {
    		return list;
    	}
}