/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.autoconfigure.web.reactive;

import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.netty.http.server.HttpServer;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.web.embedded.jetty.JettyReactiveWebServerFactory;
import org.springframework.boot.web.embedded.jetty.JettyServerCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatContextCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatReactiveWebServerFactory;
import org.springframework.boot.web.embedded.undertow.UndertowBuilderCustomizer;
import org.springframework.boot.web.embedded.undertow.UndertowDeploymentInfoCustomizer;
import org.springframework.boot.web.embedded.undertow.UndertowReactiveWebServerFactory;
import org.springframework.boot.web.reactive.context.AnnotationConfigReactiveWebApplicationContext;
import org.springframework.boot.web.reactive.context.AnnotationConfigReactiveWebServerApplicationContext;
import org.springframework.boot.web.reactive.server.ConfigurableReactiveWebServerFactory;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link ReactiveWebServerFactoryAutoConfiguration}.
 *
 * @author Brian Clozel
 * @author Raheela Aslam
 * @author Madhura Bhave
 */
class ReactiveWebServerFactoryAutoConfigurationTests {

	private ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner(
			AnnotationConfigReactiveWebServerApplicationContext::new)
					.withConfiguration(AutoConfigurations.of(ReactiveWebServerFactoryAutoConfiguration.class));

	@Test
	void createFromConfigClass() {
		this.contextRunner.withUserConfiguration(MockWebServerConfiguration.class, HttpHandlerConfiguration.class)
				.run((context) -> {
					assertThat(context.getBeansOfType(ReactiveWebServerFactory.class)).hasSize(1);
					assertThat(context.getBeansOfType(WebServerFactoryCustomizer.class)).hasSize(2);
					assertThat(context.getBeansOfType(ReactiveWebServerFactoryCustomizer.class)).hasSize(1);
				});
	}

	@Test
	void missingHttpHandler() {
		this.contextRunner.withUserConfiguration(MockWebServerConfiguration.class)
				.run((context) -> assertThat(context.getStartupFailure())
						.isInstanceOf(ApplicationContextException.class)
						.hasMessageContaining("missing HttpHandler bean"));
	}

	@Test
	void multipleHttpHandler() {
		this.contextRunner
				.withUserConfiguration(MockWebServerConfiguration.class, HttpHandlerConfiguration.class,
						TooManyHttpHandlers.class)
				.run((context) -> assertThat(context.getStartupFailure())
						.isInstanceOf(ApplicationContextException.class)
						.hasMessageContaining("multiple HttpHandler beans : " + "httpHandler,additionalHttpHandler"));
	}

	@Test
	void customizeReactiveWebServer() {
		this.contextRunner
				.withUserConfiguration(MockWebServerConfiguration.class, HttpHandlerConfiguration.class,
						ReactiveWebServerCustomization.class)
				.run((context) -> assertThat(context.getBean(MockReactiveWebServerFactory.class).getPort())
						.isEqualTo(9000));
	}

	@Test
	void defaultWebServerIsTomcat() {
		// Tomcat should be chosen over Netty if the Tomcat library is present.
		this.contextRunner.withUserConfiguration(HttpHandlerConfiguration.class).withPropertyValues("server.port=0")
				.run((context) -> assertThat(context.getBean(ReactiveWebServerFactory.class))
						.isInstanceOf(TomcatReactiveWebServerFactory.class));
	}

	@Test
	void tomcatConnectorCustomizerBeanIsAddedToFactory() {
		ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner(
				AnnotationConfigReactiveWebServerApplicationContext::new)
						.withConfiguration(AutoConfigurations.of(ReactiveWebServerFactoryAutoConfiguration.class))
						.withUserConfiguration(HttpHandlerConfiguration.class,
								TomcatConnectorCustomizerConfiguration.class)
						.withPropertyValues("server.port: 0");
		runner.run((context) -> {
			TomcatReactiveWebServerFactory factory = context.getBean(TomcatReactiveWebServerFactory.class);
			TomcatConnectorCustomizer customizer = context.getBean("connectorCustomizer",
					TomcatConnectorCustomizer.class);
			assertThat(factory.getTomcatConnectorCustomizers()).contains(customizer);
			verify(customizer, times(1)).customize(any(Connector.class));
		});
	}

	@Test
	void tomcatConnectorCustomizerRegisteredAsBeanAndViaFactoryIsOnlyCalledOnce() {
		ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner(
				AnnotationConfigReactiveWebServerApplicationContext::new)
						.withConfiguration(AutoConfigurations.of(ReactiveWebServerFactoryAutoConfiguration.class))
						.withUserConfiguration(HttpHandlerConfiguration.class,
								DoubleRegistrationTomcatConnectorCustomizerConfiguration.class)
						.withPropertyValues("server.port: 0");
		runner.run((context) -> {
			TomcatReactiveWebServerFactory factory = context.getBean(TomcatReactiveWebServerFactory.class);
			TomcatConnectorCustomizer customizer = context.getBean("connectorCustomizer",
					TomcatConnectorCustomizer.class);
			assertThat(factory.getTomcatConnectorCustomizers()).contains(customizer);
			verify(customizer, times(1)).customize(any(Connector.class));
		});
	}

	@Test
	void tomcatContextCustomizerBeanIsAddedToFactory() {
		ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner(
				AnnotationConfigReactiveWebServerApplicationContext::new)
						.withConfiguration(AutoConfigurations.of(ReactiveWebServerFactoryAutoConfiguration.class))
						.withUserConfiguration(HttpHandlerConfiguration.class,
								TomcatContextCustomizerConfiguration.class)
						.withPropertyValues("server.port: 0");
		runner.run((context) -> {
			TomcatReactiveWebServerFactory factory = context.getBean(TomcatReactiveWebServerFactory.class);
			TomcatContextCustomizer customizer = context.getBean("contextCustomizer", TomcatContextCustomizer.class);
			assertThat(factory.getTomcatContextCustomizers()).contains(customizer);
			verify(customizer, times(1)).customize(any(Context.class));
		});
	}

	@Test
	void tomcatContextCustomizerRegisteredAsBeanAndViaFactoryIsOnlyCalledOnce() {
		ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner(
				AnnotationConfigReactiveWebServerApplicationContext::new)
						.withConfiguration(AutoConfigurations.of(ReactiveWebServerFactoryAutoConfiguration.class))
						.withUserConfiguration(HttpHandlerConfiguration.class,
								DoubleRegistrationTomcatContextCustomizerConfiguration.class)
						.withPropertyValues("server.port: 0");
		runner.run((context) -> {
			TomcatReactiveWebServerFactory factory = context.getBean(TomcatReactiveWebServerFactory.class);
			TomcatContextCustomizer customizer = context.getBean("contextCustomizer", TomcatContextCustomizer.class);
			assertThat(factory.getTomcatContextCustomizers()).contains(customizer);
			verify(customizer, times(1)).customize(any(Context.class));
		});
	}

	@Test
	void tomcatProtocolHandlerCustomizerBeanIsAddedToFactory() {
		ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner(
				AnnotationConfigReactiveWebServerApplicationContext::new)
						.withConfiguration(AutoConfigurations.of(ReactiveWebServerFactoryAutoConfiguration.class))
						.withUserConfiguration(HttpHandlerConfiguration.class,
								TomcatProtocolHandlerCustomizerConfiguration.class)
						.withPropertyValues("server.port: 0");
		runner.run((context) -> {
			TomcatReactiveWebServerFactory factory = context.getBean(TomcatReactiveWebServerFactory.class);
			TomcatProtocolHandlerCustomizer<?> customizer = context.getBean("protocolHandlerCustomizer",
					TomcatProtocolHandlerCustomizer.class);
			assertThat(factory.getTomcatProtocolHandlerCustomizers()).contains(customizer);
			verify(customizer, times(1)).customize(any());
		});
	}

	@Test
	void tomcatProtocolHandlerCustomizerRegisteredAsBeanAndViaFactoryIsOnlyCalledOnce() {
		ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner(
				AnnotationConfigReactiveWebServerApplicationContext::new)
						.withConfiguration(AutoConfigurations.of(ReactiveWebServerFactoryAutoConfiguration.class))
						.withUserConfiguration(HttpHandlerConfiguration.class,
								DoubleRegistrationTomcatProtocolHandlerCustomizerConfiguration.class)
						.withPropertyValues("server.port: 0");
		runner.run((context) -> {
			TomcatReactiveWebServerFactory factory = context.getBean(TomcatReactiveWebServerFactory.class);
			TomcatProtocolHandlerCustomizer<?> customizer = context.getBean("protocolHandlerCustomizer",
					TomcatProtocolHandlerCustomizer.class);
			assertThat(factory.getTomcatProtocolHandlerCustomizers()).contains(customizer);
			verify(customizer, times(1)).customize(any());
		});
	}

	@Test
	void jettyServerCustomizerBeanIsAddedToFactory() {
		new ReactiveWebApplicationContextRunner(AnnotationConfigReactiveWebApplicationContext::new)
				.withConfiguration(AutoConfigurations.of(ReactiveWebServerFactoryAutoConfiguration.class))
				.withClassLoader(new FilteredClassLoader(Tomcat.class, HttpServer.class))
				.withUserConfiguration(JettyServerCustomizerConfiguration.class, HttpHandlerConfiguration.class)
				.run((context) -> {
					JettyReactiveWebServerFactory factory = context.getBean(JettyReactiveWebServerFactory.class);
					assertThat(factory.getServerCustomizers()).hasSize(1);
				});
	}

	@Test
	void undertowDeploymentInfoCustomizerBeanIsAddedToFactory() {
		new ReactiveWebApplicationContextRunner(AnnotationConfigReactiveWebApplicationContext::new)
				.withConfiguration(AutoConfigurations.of(ReactiveWebServerFactoryAutoConfiguration.class))
				.withClassLoader(new FilteredClassLoader(Tomcat.class, HttpServer.class, Server.class))
				.withUserConfiguration(UndertowDeploymentInfoCustomizerConfiguration.class,
						HttpHandlerConfiguration.class)
				.run((context) -> {
					UndertowReactiveWebServerFactory factory = context.getBean(UndertowReactiveWebServerFactory.class);
					assertThat(factory.getDeploymentInfoCustomizers()).hasSize(1);
				});
	}

	@Test
	void undertowBuilderCustomizerBeanIsAddedToFactory() {
		new ReactiveWebApplicationContextRunner(AnnotationConfigReactiveWebApplicationContext::new)
				.withConfiguration(AutoConfigurations.of(ReactiveWebServerFactoryAutoConfiguration.class))
				.withClassLoader(new FilteredClassLoader(Tomcat.class, HttpServer.class, Server.class))
				.withUserConfiguration(UndertowBuilderCustomizerConfiguration.class, HttpHandlerConfiguration.class)
				.run((context) -> {
					UndertowReactiveWebServerFactory factory = context.getBean(UndertowReactiveWebServerFactory.class);
					assertThat(factory.getBuilderCustomizers()).hasSize(1);
				});
	}

	@Test
	void forwardedHeaderTransformerShouldBeConfigured() {
		this.contextRunner.withUserConfiguration(HttpHandlerConfiguration.class)
				.withPropertyValues("server.forward-headers-strategy=framework")
				.run((context) -> assertThat(context).hasSingleBean(ForwardedHeaderTransformer.class));
	}

	@Test
	void forwardedHeaderTransformerWhenStrategyNotFilterShouldNotBeConfigured() {
		this.contextRunner.withUserConfiguration(HttpHandlerConfiguration.class)
				.withPropertyValues("server.forward-headers-strategy=native")
				.run((context) -> assertThat(context).doesNotHaveBean(ForwardedHeaderTransformer.class));
	}

	@Test
	void forwardedHeaderTransformerWhenAlreadyRegisteredShouldBackOff() {
		this.contextRunner
				.withUserConfiguration(ForwardedHeaderTransformerConfiguration.class, HttpHandlerConfiguration.class)
				.withPropertyValues("server.forward-headers-strategy=framework")
				.run((context) -> assertThat(context).hasSingleBean(ForwardedHeaderTransformer.class));
	}

	@Configuration(proxyBeanMethods = false)
	protected static class HttpHandlerConfiguration {

		@Bean
		public HttpHandler httpHandler() {
			return Mockito.mock(HttpHandler.class);
		}

	}

	@Configuration(proxyBeanMethods = false)
	protected static class TooManyHttpHandlers {

		@Bean
		public HttpHandler additionalHttpHandler() {
			return Mockito.mock(HttpHandler.class);
		}

	}

	@Configuration(proxyBeanMethods = false)
	protected static class ReactiveWebServerCustomization {

		@Bean
		public WebServerFactoryCustomizer<ConfigurableReactiveWebServerFactory> reactiveWebServerCustomizer() {
			return (factory) -> factory.setPort(9000);
		}

	}

	@Configuration(proxyBeanMethods = false)
	public static class MockWebServerConfiguration {

		@Bean
		public MockReactiveWebServerFactory mockReactiveWebServerFactory() {
			return new MockReactiveWebServerFactory();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class TomcatConnectorCustomizerConfiguration {

		@Bean
		public TomcatConnectorCustomizer connectorCustomizer() {
			return mock(TomcatConnectorCustomizer.class);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class DoubleRegistrationTomcatConnectorCustomizerConfiguration {

		private final TomcatConnectorCustomizer customizer = mock(TomcatConnectorCustomizer.class);

		@Bean
		public TomcatConnectorCustomizer connectorCustomizer() {
			return this.customizer;
		}

		@Bean
		public WebServerFactoryCustomizer<TomcatReactiveWebServerFactory> tomcatCustomizer() {
			return (tomcat) -> tomcat.addConnectorCustomizers(this.customizer);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class TomcatContextCustomizerConfiguration {

		@Bean
		public TomcatContextCustomizer contextCustomizer() {
			return mock(TomcatContextCustomizer.class);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class DoubleRegistrationTomcatContextCustomizerConfiguration {

		private final TomcatContextCustomizer customizer = mock(TomcatContextCustomizer.class);

		@Bean
		public TomcatContextCustomizer contextCustomizer() {
			return this.customizer;
		}

		@Bean
		public WebServerFactoryCustomizer<TomcatReactiveWebServerFactory> tomcatCustomizer() {
			return (tomcat) -> tomcat.addContextCustomizers(this.customizer);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class TomcatProtocolHandlerCustomizerConfiguration {

		@Bean
		public TomcatProtocolHandlerCustomizer<?> protocolHandlerCustomizer() {
			return mock(TomcatProtocolHandlerCustomizer.class);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class DoubleRegistrationTomcatProtocolHandlerCustomizerConfiguration {

		private final TomcatProtocolHandlerCustomizer<?> customizer = mock(TomcatProtocolHandlerCustomizer.class);

		@Bean
		public TomcatProtocolHandlerCustomizer<?> protocolHandlerCustomizer() {
			return this.customizer;
		}

		@Bean
		public WebServerFactoryCustomizer<TomcatReactiveWebServerFactory> tomcatCustomizer() {
			return (tomcat) -> tomcat.addProtocolHandlerCustomizers(this.customizer);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class JettyServerCustomizerConfiguration {

		@Bean
		public JettyServerCustomizer serverCustomizer() {
			return (server) -> {

			};
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class UndertowBuilderCustomizerConfiguration {

		@Bean
		public UndertowBuilderCustomizer builderCustomizer() {
			return (builder) -> {

			};
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class UndertowDeploymentInfoCustomizerConfiguration {

		@Bean
		public UndertowDeploymentInfoCustomizer deploymentInfoCustomizer() {
			return (deploymentInfo) -> {

			};
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ForwardedHeaderTransformerConfiguration {

		@Bean
		public ForwardedHeaderTransformer testForwardedHeaderTransformer() {
			return new ForwardedHeaderTransformer();
		}

	}

}
