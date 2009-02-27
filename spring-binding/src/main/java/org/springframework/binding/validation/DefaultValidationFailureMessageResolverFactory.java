/*
 * Copyright 2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.binding.validation;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import org.springframework.binding.collection.StringKeyedMapAdapter;
import org.springframework.binding.convert.ConversionService;
import org.springframework.binding.expression.Expression;
import org.springframework.binding.expression.ExpressionParser;
import org.springframework.binding.expression.support.FluentParserContext;
import org.springframework.binding.message.Message;
import org.springframework.binding.message.MessageResolver;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.util.Assert;

/**
 * Maps validation failures to messages resolvable in a {@link MessageSource}. Configurable with an
 * {@link ExpressionParser} to provide support messages that contain #{expressions} for inserting failure arguments.
 * Configurable with a {@link ConversionService} to support String-encoding failure arguments.
 * 
 * Employs the following algorithm to map property validation failure to a message:
 * <ol>
 * <li>Try the ${failureMessageCodePrefix}.${objectName}.${propertyName}.${constraint} code; if match, resolve message
 * and return.
 * <li>Try the ${failureMessageCodePrefix}.${propertyType}.${constraint} code; if matched, resolve message and return.
 * <li>Try the ${failureMessageCodePrefix}.${constrant} code; if matched, resolve message and return.
 * </ol>
 * 
 * Named message arguments may be denoted by using ${} or #{} expressions. For property validation failures, the value
 * of the "label" argument is automatically mapped to the message with code
 * ${labelMessageCodePrefix}.${objectName}.${propertyName}.
 * 
 * messages.properties example:
 * 
 * <pre>
 * validation.booking.checkinDate.required=The Checkin Date is required
 * validation.java.util.Date.required=Dates are required
 * validation.required=#{label} is required
 * 
 * label.booking.checkoutDate=Check Out Date
 * </pre>
 * @author Keith Donald
 */
public class DefaultValidationFailureMessageResolverFactory implements ValidationFailureMessageResolverFactory {

	private static final char CODE_SEPARATOR = '.';

	private ExpressionParser expressionParser;

	private ConversionService conversionService;

	private String failureMessageCodePrefix = "validation";

	private String labelMessageCodePrefix = "label";

	/**
	 * Creates a new message resolver factory.
	 * @param expressionParser the expression parser
	 * @param conversionService the conversion service
	 */
	public DefaultValidationFailureMessageResolverFactory(ExpressionParser expressionParser,
			ConversionService conversionService) {
		Assert.notNull(expressionParser, "The expressionParser is required");
		this.expressionParser = expressionParser;
		this.conversionService = conversionService;
	}

	/**
	 * A prefix to prepend to all validation failure message codes; default is "validation".
	 * @param failureMessageCodePrefix the failure message code prefix
	 */
	public void setFailureMessageCodePrefix(String failureMessageCodePrefix) {
		this.failureMessageCodePrefix = failureMessageCodePrefix;
	}

	/**
	 * The prefix to prepend to all property label message codes; default is "label".
	 * @param labelMessageCodePrefix the label message code prefix
	 */
	public void setLabelMessageCodePrefix(String labelMessageCodePrefix) {
		this.labelMessageCodePrefix = labelMessageCodePrefix;
	}

	public MessageResolver createMessageResolver(ValidationFailure failure, ValidationFailureModelContext modelContext) {
		return new DefaultValidationMessageResolver(failure, modelContext);
	}

	private class DefaultValidationMessageResolver implements MessageResolver {

		private static final String LABEL_ARGUMENT = "label";

		private static final String VALUE_ARGUMENT = "value";

		private ValidationFailure failure;

		private ValidationFailureModelContext modelContext;

		public DefaultValidationMessageResolver(ValidationFailure failure, ValidationFailureModelContext modelContext) {
			this.modelContext = modelContext;
			this.failure = failure;
		}

		public Message resolveMessage(MessageSource messageSource, Locale locale) {
			DefaultMessageSourceResolvable resolvable = new DefaultMessageSourceResolvable(buildCodes(), failure
					.getDefaultMessage());
			String text = messageSource.getMessage(resolvable, locale);
			Expression expression = expressionParser.parseExpression(text, new FluentParserContext()
					.evaluate(Map.class).template());
			Map stringArgs = new HashMap();
			if (!stringArgs.containsKey(LABEL_ARGUMENT)) {
				String label;
				if (failure.getPropertyName() != null) {
					label = appendLabelPrefix().append(CODE_SEPARATOR).append(modelContext.getObjectName()).append(
							CODE_SEPARATOR).append(failure.getPropertyName()).toString();
				} else {
					label = appendLabelPrefix().append(CODE_SEPARATOR).append(modelContext.getObjectName()).toString();
				}
				stringArgs.put(LABEL_ARGUMENT, new DefaultMessageSourceResolvable(new String[] { label }, failure
						.getPropertyName()));
			}
			if (!stringArgs.containsKey(VALUE_ARGUMENT) && failure.getPropertyName() != null) {
				stringArgs.put(VALUE_ARGUMENT, modelContext.getInvalidUserValue());
			}
			if (failure.getArguments() != null) {
				Iterator it = failure.getArguments().entrySet().iterator();
				while (it.hasNext()) {
					Map.Entry entry = (Map.Entry) it.next();
					Object arg = entry.getValue();
					if (!(arg instanceof String) && !(arg instanceof MessageSourceResolvable)) {
						if (modelContext.getPropertyTypeConverter() != null
								&& arg.getClass().equals(modelContext.getPropertyType())) {
							arg = conversionService.executeConversion(modelContext.getPropertyTypeConverter(), arg,
									String.class);
						} else {
							arg = conversionService.executeConversion(arg, String.class);
						}
					}
					stringArgs.put(entry.getKey(), arg);
				}
			}
			text = (String) expression.getValue(new LazyMessageResolvingMap(messageSource, locale, stringArgs));
			return new Message(failure.getPropertyName(), text, failure.getSeverity());
		}

		private String[] buildCodes() {
			String constraintMessageCode = appendMessageCodePrefix().append(CODE_SEPARATOR).append(
					failure.getConstraint()).toString();
			if (failure.getPropertyName() != null) {
				String propertyConstraintMessageCode = appendMessageCodePrefix().append(CODE_SEPARATOR).append(
						modelContext.getObjectName()).append(CODE_SEPARATOR).append(failure.getPropertyName()).append(
						CODE_SEPARATOR).append(failure.getConstraint()).toString();
				String typeConstraintMessageCode = appendMessageCodePrefix().append(CODE_SEPARATOR).append(
						modelContext.getPropertyType().getName()).append(CODE_SEPARATOR)
						.append(failure.getConstraint()).toString();
				return new String[] { propertyConstraintMessageCode, typeConstraintMessageCode, constraintMessageCode };
			} else {
				String objectConstraintMessageCode = appendMessageCodePrefix().append(CODE_SEPARATOR).append(
						modelContext.getObjectName()).append(CODE_SEPARATOR).append(failure.getConstraint()).toString();
				return new String[] { objectConstraintMessageCode, failure.getConstraint() };
			}
		}

		private StringBuilder appendMessageCodePrefix() {
			return new StringBuilder().append(failureMessageCodePrefix);
		}

		private StringBuilder appendLabelPrefix() {
			return new StringBuilder().append(labelMessageCodePrefix);
		}
	}

	private static class LazyMessageResolvingMap extends StringKeyedMapAdapter {

		private MessageSource messageSource;

		private Locale locale;

		private Map args;

		public LazyMessageResolvingMap(MessageSource messageSource, Locale locale, Map args) {
			this.messageSource = messageSource;
			this.locale = locale;
			this.args = args;
		}

		protected Object getAttribute(String key) {
			Object arg = args.get(key);
			if (arg instanceof MessageSourceResolvable) {
				arg = messageSource.getMessage(((MessageSourceResolvable) arg), locale);
				args.put(key, arg);
			}
			return arg;
		}

		protected Iterator getAttributeNames() {
			return args.keySet().iterator();
		}

		protected void removeAttribute(String key) {
			throw new UnsupportedOperationException("Should not be called");
		}

		protected void setAttribute(String key, Object value) {
			throw new UnsupportedOperationException("Should not be called");
		}

	}
}