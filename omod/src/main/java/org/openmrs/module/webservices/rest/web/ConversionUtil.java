/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.webservices.rest.web;

import java.beans.PropertyDescriptor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.APIException;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.resource.api.Converter;
import org.openmrs.module.webservices.rest.web.response.ConversionException;
import org.openmrs.util.HandlerUtil;

public class ConversionUtil {
	
	static final Log log = LogFactory.getLog(ConversionUtil.class);
	
	/**
	 * Sets all the given properties on the bean, converting them to resources as necessary.
	 * 
	 * @param bean
	 * @param properties
	 */
	public static void setConvertedProperties(Object bean, Map<String, ?> propertyMap) throws ConversionException {
		for (Map.Entry<String, ?> prop : propertyMap.entrySet()) {
			setConvertedProperty(bean, prop.getKey(), prop.getValue());
		}
	}
	
	/**
	 * Sets the given property on the bean, converting types as necessary
	 * 
	 * @param property
	 * @param value
	 * @throws ConversionException
	 */
	public static <T> void setConvertedProperty(T bean, String property, Object value) throws ConversionException {
		try {
			// first see if we have a Converter for the given object
			Converter<T> converter = (Converter<T>) getConverter(bean.getClass());
			if (converter != null) {
				converter.setProperty(bean, property, value);
			} else {
				// otherwise we try the regular method
				if (log.isTraceEnabled())
					log.trace("applying " + property + " which is a " + value.getClass() + " = " + value);
				PropertyDescriptor pd = PropertyUtils.getPropertyDescriptor(bean, property);
				if (log.isTraceEnabled())
					log.trace("property exists and is a: " + pd.getPropertyType());
				if (value == null || pd.getPropertyType().isAssignableFrom(value.getClass())) {
					if (log.isTraceEnabled())
						log.trace("compatible type, so setting directly");
					pd.getWriteMethod().invoke(bean, value);
				} else {
					if (log.isTraceEnabled())
						log.trace("need to convert " + value.getClass() + " to " + pd.getPropertyType());
					Object converted = convert(value, pd.getPropertyType());
					pd.getWriteMethod().invoke(bean, converted);
				}
			}
		}
		catch (Exception ex) {
			throw new ConversionException("setting " + property + " on " + bean.getClass(), ex);
		}
	}
	
	public static <T> Converter<T> getConverter(Class<T> clazz) {
		try {
			return HandlerUtil.getPreferredHandler(Converter.class, clazz);
		}
		catch (APIException ex) {
			return null;
		}
	}
	
	/**
	 * Converts the given object to the given type
	 * 
	 * @param object
	 * @param toType a simple class or generic type
	 * @return
	 * @throws ConversionException
	 */
	@SuppressWarnings( { "rawtypes", "unchecked" })
	public static Object convert(Object object, Type toType) throws ConversionException {
		if (object == null)
			return object;
		
		Class<?> toClass = toType instanceof Class ? ((Class<?>) toType) : (Class<?>) (((ParameterizedType) toType)
		        .getRawType());
		
		// if we're trying to convert _to_ a collection, handle it as a special case
		if (Collection.class.isAssignableFrom(toClass)) {
			if (!(object instanceof Collection))
				throw new ConversionException("Can only convert a Collection to a Collection. Not " + object.getClass()
				        + " to " + toType, null);
			
			Collection ret = null;
			if (SortedSet.class.isAssignableFrom(toClass))
				ret = new TreeSet();
			else if (Set.class.isAssignableFrom(toClass))
				ret = new HashSet();
			else if (List.class.isAssignableFrom(toClass))
				ret = new ArrayList();
			else
				throw new ConversionException("Don't know how to handle collection class: " + toClass, null);
			
			if (toType instanceof ParameterizedType) {
				// if we have generic type information for the target collection, we can use it to do conversion
				ParameterizedType toParameterizedType = (ParameterizedType) toType;
				Type targetElementType = toParameterizedType.getActualTypeArguments()[0];
				for (Object element : (Collection) object)
					ret.add(convert(element, targetElementType));
			} else {
				// otherwise we must just add all items in a non-type-safe manner
				ret.addAll((Collection) object);
			}
			return ret;
		}
		
		// otherwise we're converting _to_ a non-collection type
		
		if (toClass.isAssignableFrom(object.getClass()))
			return object;
		
		if (object instanceof String) {
			String string = (String) object;
			Converter<?> converter = getConverter(toClass);
			if (converter != null)
				return converter.getByUniqueId(string);
			
			if (toClass.isAssignableFrom(Date.class)) {
				ParseException pex = null;
				String[] supportedFormats = { "yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ss.SSS",
				        "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd" };
				for (int i = 0; i < supportedFormats.length; i++) {
					try {
						Date date = new SimpleDateFormat(supportedFormats[i]).parse(string);
						return date;
					}
					catch (ParseException ex) {
						pex = ex;
					}
				}
				throw new ConversionException(
				        "Error converting date - correct format (ISO8601 Long): yyyy-MM-dd'T'HH:mm:ss.SSSZ", pex);
			}
		} else if (object instanceof Map) {
			Object ret;
			try {
				ret = toClass.newInstance();
			}
			catch (Exception ex) {
				throw new ConversionException("instantiating " + toType + " (actually " + toClass + ")", ex);
			}
			setConvertedProperties(ret, (Map<String, ?>) object);
			return ret;
		}
		throw new ConversionException("Don't know how to convert from " + object.getClass() + " to " + toType, null);
	}
	
	/**
	 * Gets a property from the delegate, with the given representation
	 * 
	 * @param propertyName
	 * @param rep
	 * @return
	 * @throws ConversionException
	 */
	public static Object getPropertyWithRepresentation(Object bean, String propertyName, Representation rep)
	        throws ConversionException {
		Object o;
		try {
			o = PropertyUtils.getProperty(bean, propertyName);
		}
		catch (Exception ex) {
			throw new ConversionException(null, ex);
		}
		if (o instanceof Collection) {
			List<Object> ret = new ArrayList<Object>();
			for (Object element : (Collection<?>) o)
				ret.add(convertToRepresentation(element, rep));
			return ret;
		} else {
			o = convertToRepresentation(o, rep);
			return o;
		}
	}
	
	@SuppressWarnings("unchecked")
	public static <S> Object convertToRepresentation(S o, Representation rep) throws ConversionException {
		if (o == null)
			return null;
		Converter<S> converter = null;
		try {
			converter = HandlerUtil.getPreferredHandler(Converter.class, o.getClass());
		}
		catch (APIException ex) {
			// try a few known datatypes
			if (o instanceof Date) {
				return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format((Date) o);
			}
			// otherwise we have no choice but to return the plain object
			return o;
		}
		try {
			converter = converter.getClass().newInstance();
			return converter.asRepresentation(o, rep);
		}
		catch (Exception ex) {
			throw new ConversionException("converting " + o.getClass() + " to " + rep, ex);
		}
	}
	
}
