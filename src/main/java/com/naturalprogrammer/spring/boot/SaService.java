package com.naturalprogrammer.spring.boot;

import java.io.Serializable;
import java.util.UUID;

import javax.mail.MessagingException;
import javax.validation.ConstraintViolationException;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.validator.constraints.Email;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;

import com.naturalprogrammer.spring.boot.BaseUser.Role;
import com.naturalprogrammer.spring.boot.mail.MailSender;
import com.naturalprogrammer.spring.boot.security.UserDto;
import com.naturalprogrammer.spring.boot.validation.FieldError;
import com.naturalprogrammer.spring.boot.validation.FormException;

@Validated
@Transactional(propagation=Propagation.SUPPORTS, readOnly=true)
public abstract class SaService<U extends BaseUser<U,ID>, ID extends Serializable, S extends SignupForm> {

    private final Log log = LogFactory.getLog(getClass());
    
	@Value(SaUtil.APPLICATION_URL)
    private String applicationUrl;
	
	@Value(SaUtil.RECAPTCHA_SITE_KEY)
    private String reCaptchaSiteKey;
	
	@Value("${admin.email}")
	private String adminEmail;

	@Value("${admin.password}")
	private String adminPassword;

	@Autowired
    private PasswordEncoder passwordEncoder;

	@Autowired
    private MailSender mailSender;

    @Autowired
	private BaseUserRepository<U, ID> userRepository;
    
    
    /**
     * This method needs to be public; otherwise Spring screams
     * 
     * @param event
     */
    @EventListener
	@Transactional(propagation=Propagation.REQUIRED, readOnly=false)
    public void afterContextRefreshed(ContextRefreshedEvent event) {
    	
    	onStartup();
    	
    }
    
	/**
	 * Override this if needed
	 */
    protected void onStartup() {
		U user = userRepository.findByEmail(adminEmail);
		if (user == null) {
	    	user = createAdminUser();
	    	userRepository.save(user);
		}
	}

	
	/**
	 * Override this if you have more fields to set
	 */
    protected U createAdminUser() {
		
		final U user = (U) SaUtil.getBean(BaseUser.class);
		
		user.setEmail(adminEmail);
		user.setName("Administrator");
		user.setPassword(passwordEncoder.encode(adminPassword));
		user.getRoles().add(Role.ADMIN);
		
		return user;

	}


	public ContextDto getContext() {
		ContextDto contextDto = new ContextDto();
		contextDto.setReCaptchaSiteKey(reCaptchaSiteKey);
		contextDto.setUserDto(SaUtil.getUserDto());
		return contextDto;		
	}
	
	@PreAuthorize("isAnonymous()")
	@Transactional(propagation=Propagation.REQUIRED, readOnly=false)
	public UserDto<ID> signup(@Valid S signupForm) {
		
		U user = createUser(signupForm);
		userRepository.save(user);
		sendVerificationMail(user);
		SaUtil.logInUser(user);
		return user.getUserDto();
	}
	
	protected U createUser(S signupForm) {
		
		final U user = (U) SaUtil.getBean(BaseUser.class);
		
		user.setEmail(signupForm.getEmail());
		user.setName(signupForm.getName());
		user.setPassword(passwordEncoder.encode(signupForm.getPassword()));
		user.getRoles().add(Role.UNVERIFIED);
		user.setVerificationCode(UUID.randomUUID().toString());
		
		return user;
		
	}
	
	protected void sendVerificationMail(final U user) {
		TransactionSynchronizationManager.registerSynchronization(
			    new TransactionSynchronizationAdapter() {
			        @Override
			        public void afterCommit() {
			    		try {
			    			String verifyLink = applicationUrl + "/users/" + user.getVerificationCode() + "/verify";
			    			mailSender.send(user.getEmail(), SaUtil.getMessage("verifySubject"), SaUtil.getMessage("verifyEmail", verifyLink));
			    			log.info("Verification mail to " + user.getEmail() + " queued.");
						} catch (MessagingException e) {
							log.error(ExceptionUtils.getStackTrace(e));
						}
			        }
			    });
			
	}

	public U fetchUser(@Valid @Email @NotNull String email) {
		
		U loggedIn = SaUtil.getSessionUser();
		U user = userRepository.findByEmail(email);
		
		if (user == null) {
			throw new FormException("email", "userNotFound");
		}

		user.setPassword(null);
		
		if (loggedIn == null ||	loggedIn.getId() != user.getId() && !loggedIn.isAdmin())
				user.setEmail("Confidential");
		
		return user;
	}

	
	@Transactional(propagation=Propagation.REQUIRED, readOnly=false)
	public void verifyUser(String verificationCode) {
		
		U user = userRepository.findByVerificationCode(verificationCode);
		SaUtil.validate(user != null, "userNotFound");
		
		user.setVerificationCode(null);
		user.getRoles().remove(Role.UNVERIFIED);
		userRepository.save(user);
		
	}

	@Transactional(propagation=Propagation.REQUIRED, readOnly=false)
	public void forgotPassword(@Valid @Email @NotNull String email) {
		
		final U user = userRepository.findByEmail(email);

		SaUtil.validate(user != null, "userNotFound");
		
		user.setForgotPasswordCode(UUID.randomUUID().toString());
		userRepository.save(user);

		TransactionSynchronizationManager.registerSynchronization(
			    new TransactionSynchronizationAdapter() {
			        @Override
			        public void afterCommit() {
			        	try {
							mailForgotPasswordLink(user);
						} catch (MessagingException e) {
							log.error(ExceptionUtils.getStackTrace(e));
						}
			        }

		    });				
	}
	
	private void mailForgotPasswordLink(U user) throws MessagingException {
		
		String forgotPasswordLink = 
				applicationUrl + "/reset-password/" +
				user.getForgotPasswordCode();
		mailSender.send(user.getEmail(),
				SaUtil.getMessage("forgotPasswordSubject"),
				SaUtil.getMessage("forgotPasswordEmail", forgotPasswordLink));

	}


}
